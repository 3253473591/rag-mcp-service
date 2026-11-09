package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.UploadProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text PDF parsing (docs/11-retrieval-and-eval-spec.md 1.2).
 *
 * <p>Heading priority: PDF outline/bookmark -&gt; font &gt; 1.2x body median and bold -&gt;
 * a cleaned {@code 第X章/节/条} or {@code ^\d+(\.\d+)*\s} candidate -&gt; no heading
 * (headless documents fall back to fixed chunking downstream).</p>
 *
 * <p><b>Double-column reading order.</b> A row-major PDFTextStripper sort interleaves the left
 * and right columns of a journal page, which used to make table fragments such as
 * {@code 90 mmHg} look like headings. This parser reconstructs physical line segments, finds
 * the vertical gutter, splits lines that cross it, and emits the complete left column before
 * the right column. Pages without a gutter (single-column DOCX-like layouts) keep normal
 * top-to-bottom order.</p>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "^第[\\s\\u3000]*[一二三四五六七八九十百千零〇两0-9０-９]+[\\s\\u3000]*([章节条])");
    private static final int MAX_HEADING_LENGTH = 120;
    private static final float LINE_Y_TOLERANCE = 2.0f;
    private static final float MIN_GUTTER_WIDTH = 8.0f;

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
        // We reorder segments ourselves. Enabling PDFBox's row-major sort would merge the two
        // columns into one line before we can see the gutter.
        collector.setSortByPosition(false);
        collector.setStartPage(1);
        collector.setEndPage(document.getNumberOfPages());
        collector.getText(document);
        return layout(collector.segments(), collector.pageWidths());
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

    /**
     * Reconstruct lines page by page. Two-column pages are emitted left column first, then right
     * column; a header/footer that spans the gutter is split so it cannot move body text across
     * columns. Single-column pages are grouped by y and sorted left-to-right as before.
     */
    static List<PdfLine> layout(List<PdfSegment> segments, Map<Integer, Float> pageWidths) {
        Map<Integer, List<PdfSegment>> byPage = new LinkedHashMap<>();
        for (PdfSegment segment : segments) {
            if (segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            byPage.computeIfAbsent(segment.page(), ignored -> new ArrayList<>()).add(segment);
        }
        List<PdfLine> lines = new ArrayList<>();
        List<Integer> pages = new ArrayList<>(byPage.keySet());
        pages.sort(Integer::compareTo);
        for (Integer pageNo : pages) {
            List<PdfSegment> pageSegments = byPage.get(pageNo);
            float width = pageWidths.getOrDefault(pageNo, 595.0f);
            float gutter = findGutter(pageSegments, width);
            if (gutter > 0) {
                List<PdfSegment> split = new ArrayList<>(pageSegments.size() + 4);
                for (PdfSegment segment : pageSegments) {
                    splitSegment(segment, gutter, split);
                }
                List<PdfSegment> left = new ArrayList<>();
                List<PdfSegment> right = new ArrayList<>();
                for (PdfSegment segment : split) {
                    if (segment.x2() <= gutter) {
                        left.add(segment);
                    } else if (segment.x() >= gutter) {
                        right.add(segment);
                    } else {
                        // A short segment that starts in the gutter belongs to the nearer column.
                        (segment.x() < gutter ? left : right).add(segment);
                    }
                }
                // Column-major order is intentional: page 4 of a two-column journal must read
                // the complete left column before the complete right column.
                lines.addAll(toLines(pageNo, left));
                lines.addAll(toLines(pageNo, right));
            } else {
                lines.addAll(toLines(pageNo, pageSegments));
            }
        }
        return lines;
    }

    /**
     * Find the vertical gutter between two columns using the end of left-column lines and the
     * start of right-column lines. Full-width table/header lines are intentionally ignored:
     * a page can have a table that spans both columns and still be a two-column page above it.
     */
    static float findGutter(List<PdfSegment> segments, float pageWidth) {
        if (segments.size() < 8) {
            return -1.0f;
        }
        float leftStartLimit = pageWidth * 0.45f;
        float leftEndLimit = pageWidth * 0.60f;
        float rightStartLimit = pageWidth * 0.50f;
        List<Float> leftEnds = new ArrayList<>();
        List<Float> rightStarts = new ArrayList<>();
        for (PdfSegment segment : segments) {
            if (segment.x() < leftStartLimit && segment.x2() <= leftEndLimit) {
                leftEnds.add(segment.x2());
            } else if (segment.x() >= rightStartLimit) {
                rightStarts.add(segment.x());
            }
        }
        if (leftEnds.size() < 3 || rightStarts.size() < 3) {
            return -1.0f;
        }
        leftEnds.sort(Float::compareTo);
        rightStarts.sort(Float::compareTo);
        // Use the 90th/10th percentile so one wide table cell does not swallow the gutter.
        float maxLeft = leftEnds.get(Math.min(leftEnds.size() - 1,
                (int) Math.floor(leftEnds.size() * 0.90f)));
        float minRight = rightStarts.get(Math.min(rightStarts.size() - 1,
                (int) Math.floor(rightStarts.size() * 0.10f)));
        if (minRight - maxLeft < MIN_GUTTER_WIDTH) {
            return -1.0f;
        }
        float gutter = (maxLeft + minRight) / 2.0f;
        return gutter >= pageWidth * 0.35f && gutter <= pageWidth * 0.65f ? gutter : -1.0f;
    }

    private static void splitSegment(PdfSegment segment, float gutter, List<PdfSegment> target) {
        if (segment.x() >= gutter || segment.x2() <= gutter) {
            target.add(segment);
            return;
        }
        int split = splitIndex(segment, gutter);
        String leftText = segment.text().substring(0, split).stripTrailing();
        String rightText = segment.text().substring(split).stripLeading();
        if (!leftText.isEmpty()) {
            target.add(segment.withText(leftText, segment.x(), Math.min(segment.x2(), gutter - 0.1f)));
        }
        if (!rightText.isEmpty()) {
            target.add(segment.withText(rightText, Math.max(segment.x(), gutter),
                    Math.max(segment.x2(), gutter)));
        }
    }

    private static int splitIndex(PdfSegment segment, float gutter) {
        List<TextPosition> positions = segment.positions();
        if (positions == null || positions.isEmpty()) {
            return segment.text().length() / 2;
        }
        int crossing = 0;
        for (TextPosition position : positions) {
            if (position.getXDirAdj() < gutter) {
                crossing++;
            }
        }
        if (positions.size() == segment.text().length()) {
            for (int i = 0; i < positions.size(); i++) {
                if (positions.get(i).getXDirAdj() >= gutter) {
                    return i;
                }
            }
            return segment.text().length();
        }
        int index = (int) Math.round(segment.text().length() * crossing / (double) positions.size());
        return Math.max(0, Math.min(segment.text().length(), index));
    }

    private static List<PdfLine> toLines(int page, List<PdfSegment> segments) {
        List<PdfSegment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingDouble(PdfSegment::y).thenComparingDouble(PdfSegment::x));
        List<PdfLine> lines = new ArrayList<>();
        List<PdfSegment> group = new ArrayList<>();
        float groupY = Float.NaN;
        for (PdfSegment segment : sorted) {
            if (segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            if (group.isEmpty() || Math.abs(segment.y() - groupY) <= LINE_Y_TOLERANCE) {
                group.add(segment);
                if (group.size() == 1) {
                    groupY = segment.y();
                }
            } else {
                lines.add(toLine(page, group));
                group = new ArrayList<>();
                group.add(segment);
                groupY = segment.y();
            }
        }
        if (!group.isEmpty()) {
            lines.add(toLine(page, group));
        }
        lines.sort(Comparator.comparingDouble(PdfLine::y).thenComparingDouble(PdfLine::x));
        return lines;
    }

    private static PdfLine toLine(int page, List<PdfSegment> group) {
        group.sort(Comparator.comparingDouble(PdfSegment::x));
        StringBuilder text = new StringBuilder();
        float maxFontSize = 0.0f;
        boolean bold = false;
        float x = Float.MAX_VALUE;
        float y = Float.MAX_VALUE;
        for (PdfSegment segment : group) {
            String value = segment.text().strip();
            if (value.isEmpty()) {
                continue;
            }
            if (text.length() > 0 && needsSpace(text.charAt(text.length() - 1), value.charAt(0))) {
                text.append(' ');
            }
            text.append(value);
            maxFontSize = Math.max(maxFontSize, segment.fontSize());
            bold = bold || segment.bold();
            x = Math.min(x, segment.x());
            y = Math.min(y, segment.y());
        }
        return new PdfLine(page, text.toString().strip(), maxFontSize, bold, x, y);
    }

    private List<Block> buildBlocks(List<PdfLine> lines, Map<String, Integer> outlineLevels) {
        float medianFontSize = medianFontSize(lines);
        boolean hasDistinctHeadingFont = lines.stream().anyMatch(PdfLine::bold);
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
            boolean heading = outlineLevel != null
                    || isHeuristicHeading(line, medianFontSize, hasDistinctHeadingFont);
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

    private static boolean isHeuristicHeading(PdfLine line,
                                              float medianFontSize,
                                              boolean hasDistinctHeadingFont) {
        String text = line.text().trim();
        if (text.isEmpty() || text.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        // Chinese clauses are allowed to carry the first body sentence on the same line and may
        // therefore end with a full stop; do not apply the sentence-punctuation guard to them.
        if (HeadingCandidates.isArticleHeading(text)) {
            return true;
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
        if (!HeadingCandidates.isNumberedHeading(text)) {
            return false;
        }
        // Many journal PDFs use a dedicated heading font (FZXBSJW / XBS in the 2025 guideline).
        // When such a font exists, a numbered line set in body font is a table fragment even if it
        // happens to start with a number.
        return !hasDistinctHeadingFont || line.bold();
    }

    private static boolean endsWithSentencePunctuation(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；' || last == '.'
                || last == '!' || last == '?' || last == ';' || last == '，' || last == ',';
    }

    private static int inferLevel(String text) {
        Matcher article = ARTICLE_HEADING.matcher(text);
        if (article.find()) {
            return switch (article.group(1)) {
                case "章" -> 1;
                case "节" -> 2;
                default -> 3;
            };
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

    /** A line segment captured from the PDF, before column-aware reconstruction. */
    public record PdfSegment(int page,
                             String text,
                             List<TextPosition> positions,
                             float fontSize,
                             boolean bold,
                             float x,
                             float y,
                             float x2) {

        PdfSegment withText(String newText, float newX, float newX2) {
            return new PdfSegment(page, newText, positions, fontSize, bold, newX, y, newX2);
        }
    }

    /** One reconstructed physical line in reading order. */
    public record PdfLine(int page, String text, float fontSize, boolean bold, float x, float y) {
    }

    private static final class LineCollector extends PDFTextStripper {

        private final List<PdfSegment> segments = new ArrayList<>();
        private final Map<Integer, Float> pageWidths = new HashMap<>();

        private LineCollector() throws IOException {
            super();
        }

        List<PdfSegment> segments() {
            return segments;
        }

        Map<Integer, Float> pageWidths() {
            return pageWidths;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            super.startPage(page);
            pageWidths.put(getCurrentPageNo(), page.getMediaBox().getWidth());
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }
            float maxSize = 0f;
            boolean bold = false;
            float x = Float.MAX_VALUE;
            float x2 = -Float.MAX_VALUE;
            float y = Float.MAX_VALUE;
            for (TextPosition position : textPositions) {
                maxSize = Math.max(maxSize, position.getFontSizeInPt());
                x = Math.min(x, position.getXDirAdj());
                x2 = Math.max(x2, position.getXDirAdj() + position.getWidthDirAdj());
                y = Math.min(y, position.getYDirAdj());
                if (!bold) {
                    bold = isBoldFont(position);
                }
            }
            segments.add(new PdfSegment(getCurrentPageNo(), text, List.copyOf(textPositions),
                    maxSize, bold, x, y, x2));
        }

        private static boolean isBoldFont(TextPosition position) {
            try {
                if (position.getFont() == null || position.getFont().getName() == null) {
                    return false;
                }
                String name = position.getFont().getName().toLowerCase();
                return name.contains("bold") || name.contains("black") || name.contains("heavy")
                        || name.contains("xbs");
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
