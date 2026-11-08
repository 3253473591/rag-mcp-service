package com.lqx.ragmcp.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Small in-memory fixtures generated at test time (no PDF/DOCX files are committed). */
public final class TestDocuments {

    private TestDocuments() {
    }

    /** Two-page text PDF with numbered headings and body paragraphs. */
    public static byte[] pdfWithHeadings() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addPage(document, "3.2 Diagnosis criteria",
                    "Blood pressure threshold is 140 over 90 mmHg for adults.",
                    "Repeated measurement is required before diagnosis.");
            addPage(document, "3.3 Treatment principles",
                    "Lifestyle intervention is the first step.",
                    "Medication is started when risk is high.");
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Text PDF without any extractable text content (simulates a scanned document). */
    public static byte[] blankPdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void addPage(PDDocument document, String heading, String... bodyLines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            float y = 750;
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
            stream.newLineAtOffset(72, y);
            stream.showText(heading);
            stream.endText();
            y -= 30;
            for (String line : bodyLines) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(72, y);
                stream.showText(line);
                stream.endText();
                y -= 18;
            }
        }
    }

    public static byte[] docxWithHeadings() {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            paragraph(document, "1 Scope", "Heading1");
            paragraph(document, "This policy applies to all employees.", null);
            paragraph(document, "1.1 Definitions", "Heading2");
            paragraph(document, "Annual leave means paid time off.", null);
            XWPFTable table = document.createTable(2, 2);
            fillRow(table.getRow(0), "Leave type", "Days");
            fillRow(table.getRow(1), "Annual", "10");
            paragraph(document, "2 Approval", "Heading1");
            paragraph(document, "Approval is granted by the line manager.", null);
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void paragraph(XWPFDocument document, String text, String style) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }

    private static void fillRow(XWPFTableRow row, String first, String second) {
        row.getCell(0).setText(first);
        row.getCell(1).setText(second);
    }

    public static byte[] markdown() {
        String content = """
                # 员工手册

                本手册适用于全体员工。

                ## 1.1 年假

                员工每年享有十天年假。年假需提前申请。

                | 类型 | 天数 |
                | --- | --- |
                | 年假 | 10 |
                | 病假 | 5 |

                ## 1.2 病假

                病假需提供医院证明。

                ### 1.2.1 长期病假

                超过三十天的病假需人力资源审批。
                """;
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] txt() {
        String content = """
                公司差旅制度

                员工出差需提前申请，并在返回后十个工作日内报销。

                住宿标准按城市等级执行。
                """;
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] longText(int sentenceCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sentenceCount; i++) {
            builder.append("这是用于测试切分窗口的中文句子，编号").append(i)
                    .append("，包含足够多的汉字来产生 token。");
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
