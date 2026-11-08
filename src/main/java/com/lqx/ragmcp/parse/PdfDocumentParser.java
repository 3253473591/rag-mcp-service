package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.UploadProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text PDF parsing (docs/11-retrieval-and-eval-spec.md 1.2).
 *
 * <p>Heading priority: PDF outline/bookmark -&gt; font &gt; 1.2x body median and bold -&gt;
 * {@code 第X章/节/条} or {@code ^\d+(\.\d+)*\s} regex -&gt; no heading (headless documents
 * fall back to fixed chunking downstream).</p>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百]+[章节条]");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+(?:\\.\\d+)*\\s+\\S");
    private static final int MAX_HEADING_LENGTH = 120;

    private final UploadProperties properties;

    public PdfDocumentParser(UploadProperties properties) {
        this.properties = properties;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.PDF;
    }

    @Override
    public List<Block> parse(byte[] content, String basename) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw ApiException.parseFailed("PDF 已加密，无法解析");
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw ApiException.parseFailed("PDF 没有页面");
            }
            if (pageCount > properties.getMaxPdfPages()) {
                throw ApiException.parseFailed("PDF 页数超过上限（" + properties.getMaxPdfPages() + " 页）");
            }
            List<PdfLine> lines = extractLines(document);
            if (lines.isEmpty()) {
                throw ApiException.parseFailed("PDF 没有可提取文本（可能是扫描件），v1 不做 OCR");
            }
            Map<String, Integer> outlineLevels = extractOutlineLevels(document);
            return buildBlocks(lines, outlineLevels);
        } catch (InvalidPasswordException ex) {
            throw ApiException.parseFailed("PDF 已加密，无法解析");
        } catch (IOException ex) {
            log.info("pdf parse failed filename={} error={}", basename, ex.getClass().getSimpleName());
            throw ApiException.parseFailed("PDF 解析失败，文件可能已损坏");
        }
    }

    private List<PdfLine> extractLines(PDDocument document) throws IOException {
        LineCollector collector = new LineCollector();
        collector.setSortByPosition(true);
        collector.setStartPage(1);
        collector.setEndPage(document.getNumberOfPages());
        collector.getText(document);
        return collector.lines();
    }

    private Map<String, Integer> extractOutlineLevels(PDDocument document) {
        Map<String, Integer> levels = new HashMap<>();
        try {
            PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                return levels;
            }
            collectOutline(outline.getFirstChild(), 1, levels);
        } catch (Exception ex) {
            log.warn("pdf outline unavailable: {}", ex.getClass().getSimpleName());
        }
        return levels;
    }

    private void collectOutline(PDOutlineItem first, int level, Map<String, Integer> levels) {
        PDOutlineItem current = first;
        while (current != null) {
            String title = normalize(current.getTitle());
            if (!title.isEmpty()) {
                levels.putIfAbsent(title, level);
            }
            if (current.getFirstChild() != null) {
                collectOutline(current.getFirstChild(), level + 1, levels);
            }
            current = current.getNextSibling();
        }
    }

    private List<Block> buildBlocks(List<PdfLine> lines, Map<String, Integer> outlineLevels) {
        float medianFontSize = medianFontSize(lines);
        HeadingContext context = new HeadingContext();
        List<Block> blocks = new ArrayList<>();
        List<PdfLine> paragraph = new ArrayList<>();
        Integer paragraphPage = null;

        for (PdfLine line : lines) {
            String text = line.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            Integer outlineLevel = outlineLevels.get(normalize(text));
            boolean heading = outlineLevel != null || isHeuristicHeading(line, medianFontSize);
            if (heading) {
                flushParagraph(blocks, paragraph);
                paragraphPage = null;
                int level = outlineLevel != null ? outlineLevel : inferLevel(text);
                blocks.add(context.heading(level, text, line.page()));
            } else {
                if (paragraphPage != null && paragraphPage != line.page()) {
                    flushParagraph(blocks, paragraph);
                    paragraphPage = null;
                }
                if (paragraph.isEmpty()) {
                    paragraphPage = line.page();
                }
                paragraph.add(line);
            }
        }
        flushParagraph(blocks, paragraph);
        if (blocks.isEmpty()) {
            throw ApiException.parseFailed("PDF 没有可提取文本（可能是扫描件），v1 不做 OCR");
        }
        return blocks;
    }

    private static void flushParagraph(List<Block> blocks, List<PdfLine> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (PdfLine line : paragraph) {
            String current = line.text().trim();
            if (current.isEmpty()) {
                continue;
            }
            if (text.length() == 0) {
                text.append(current);
            } else if (needsSpace(text.charAt(text.length() - 1), current.charAt(0))) {
                text.append(' ').append(current);
            } else {
                text.append(current);
            }
        }
        if (!text.isEmpty()) {
            blocks.add(Block.paragraph(text.toString(), paragraph.get(0).page()));
        }
        paragraph.clear();
    }

    private static boolean needsSpace(char previous, char next) {
        return !(isCjk(previous) && isCjk(next));
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }

    private static boolean isHeuristicHeading(PdfLine line, float medianFontSize) {
        String text = line.text().trim();
        if (text.isEmpty() || text.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        if (endsWithSentencePunctuation(text)) {
            return false;
        }
        if (text.length() <= 4) {
            return false;
        }
        if (line.bold() && line.fontSize() >= medianFontSize * 1.2f && line.fontSize() > medianFontSize) {
            return true;
        }
        return CHAPTER.matcher(text).find() || NUMBERED.matcher(text).find();
    }

    private static boolean endsWithSentencePunctuation(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；' || last == '.'
                || last == '!' || last == '?' || last == ';' || last == '，' || last == ',';
    }

    private static int inferLevel(String text) {
        if (CHAPTER.matcher(text).find()) {
            return 1;
        }
        int dots = 0;
        for (int i = 0; i < text.length() && text.charAt(i) != ' '; i++) {
            if (text.charAt(i) == '.') {
                dots++;
            }
        }
        return Math.max(1, Math.min(6, dots + 1));
    }

    private static float medianFontSize(List<PdfLine> lines) {
        List<Float> sizes = new ArrayList<>(lines.size());
        for (PdfLine line : lines) {
            if (!line.text().isBlank()) {
                sizes.add(line.fontSize());
            }
        }
        if (sizes.isEmpty()) {
            return 0f;
        }
        sizes.sort(Comparator.naturalOrder());
        return sizes.get(sizes.size() / 2);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    /** One physical text line captured from the PDF. */
    record PdfLine(int page, String text, float fontSize, boolean bold) {
    }

    private static final class LineCollector extends PDFTextStripper {

        private final List<PdfLine> lines = new ArrayList<>();

        private LineCollector() throws IOException {
            super();
        }

        List<PdfLine> lines() {
            return lines;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }
            float maxSize = 0f;
            boolean bold = false;
            for (TextPosition position : textPositions) {
                maxSize = Math.max(maxSize, position.getFontSizeInPt());
                if (!bold) {
                    bold = isBoldFont(position);
                }
            }
            lines.add(new PdfLine(getCurrentPageNo(), text, maxSize, bold));
        }

        private static boolean isBoldFont(TextPosition position) {
            try {
                if (position.getFont() == null || position.getFont().getName() == null) {
                    return false;
                }
                String name = position.getFont().getName().toLowerCase();
                return name.contains("bold") || name.contains("black") || name.contains("heavy");
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
