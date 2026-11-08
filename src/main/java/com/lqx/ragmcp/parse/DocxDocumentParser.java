package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX parsing (docs/11-retrieval-and-eval-spec.md 1.3): Heading 1~6 styles first,
 * bold/large fallback, tables kept as one block with table=true. Page numbers unknown.
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("heading(\\d)");
    private static final Pattern CN_HEADING_STYLE = Pattern.compile("标题(\\d)");

    @Override
    public SourceType sourceType() {
        return SourceType.DOCX;
    }

    @Override
    public List<Block> parse(byte[] content, String basename) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            double medianFontSize = medianFontSize(document);
            HeadingContext context = new HeadingContext();
            List<Block> blocks = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    OptionalInt headingLevel = headingLevel(paragraph, medianFontSize);
                    if (headingLevel.isPresent()) {
                        blocks.add(context.heading(headingLevel.getAsInt(), text, null));
                    } else {
                        blocks.add(Block.paragraph(text.trim(), null));
                    }
                } else if (element instanceof XWPFTable table) {
                    String text = tableText(table);
                    if (!text.isBlank()) {
                        blocks.add(Block.table(text, null));
                    }
                }
            }
            if (blocks.isEmpty()) {
                throw ApiException.parseFailed("DOCX 没有可提取文本");
            }
            return blocks;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ApiException apiException) {
                throw apiException;
            }
            throw ApiException.parseFailed("DOCX 解析失败，文件可能已损坏或不是有效的 DOCX");
        }
    }

    private OptionalInt headingLevel(XWPFParagraph paragraph, double medianFontSize) {
        String style = paragraph.getStyle();
        if (style == null || style.isBlank()) {
            style = paragraph.getStyleID();
        }
        if (style != null && !style.isBlank()) {
            String normalized = style.trim().toLowerCase(Locale.ROOT).replace(" ", "");
            Matcher heading = HEADING_STYLE.matcher(normalized);
            if (heading.matches()) {
                return OptionalInt.of(clampLevel(Integer.parseInt(heading.group(1))));
            }
            Matcher cnHeading = CN_HEADING_STYLE.matcher(normalized);
            if (cnHeading.matches()) {
                return OptionalInt.of(clampLevel(Integer.parseInt(cnHeading.group(1))));
            }
            if (normalized.equals("title")) {
                return OptionalInt.of(1);
            }
        }
        // Outline level (0-based in OOXML).
        if (paragraph.getCTP().getPPr() != null && paragraph.getCTP().getPPr().isSetOutlineLvl()) {
            BigInteger value = paragraph.getCTP().getPPr().getOutlineLvl().getVal();
            int level = value.intValue() + 1;
            if (level >= 1 && level <= 6) {
                return OptionalInt.of(level);
            }
        }
        // Fallback: every non-blank run bold and clearly larger than body text.
        boolean anyText = false;
        boolean allBold = true;
        double maxSize = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText == null || runText.isBlank()) {
                continue;
            }
            anyText = true;
            allBold = allBold && run.isBold();
            int size = run.getFontSize();
            if (size > 0) {
                maxSize = Math.max(maxSize, size);
            }
        }
        if (anyText && allBold && maxSize > medianFontSize * 1.2 && paragraph.getText().length() <= 120) {
            return OptionalInt.of(1);
        }
        return OptionalInt.empty();
    }

    private double medianFontSize(XWPFDocument document) {
        List<Integer> sizes = new ArrayList<>();
        for (IBodyElement element : document.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                for (XWPFRun run : paragraph.getRuns()) {
                    int size = run.getFontSize();
                    if (size > 0 && run.text() != null && !run.text().isBlank()) {
                        sizes.add(size);
                    }
                }
            }
        }
        if (sizes.isEmpty()) {
            return 0;
        }
        sizes.sort(Comparator.naturalOrder());
        return sizes.get(sizes.size() / 2);
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(6, level));
    }

    private String tableText(XWPFTable table) {
        StringBuilder builder = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder();
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                if (cellText == null) {
                    cellText = "";
                }
                cellText = cellText.replace("\n", " ").trim();
                if (line.length() > 0) {
                    line.append(" | ");
                }
                line.append(cellText);
            }
            if (!line.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
