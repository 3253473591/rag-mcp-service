package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown parsing (docs/11-retrieval-and-eval-spec.md 1.4): ATX headings, nested heading
 * paths, tables kept whole, fenced code blocks kept as body text.
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    @Override
    public SourceType sourceType() {
        return SourceType.MD;
    }

    @Override
    public List<Block> parse(byte[] content, String basename) {
        String text = TextDecoder.decode(content);
        if (text == null) {
            throw ApiException.parseFailed("Markdown 编码无法识别（仅支持 UTF-8 / GB18030）");
        }
        if (text.isBlank()) {
            throw ApiException.parseFailed("Markdown 文件为空");
        }
        List<Block> blocks = new ArrayList<>();
        HeadingContext context = new HeadingContext();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder table = new StringBuilder();
        boolean inCodeFence = false;

        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.stripTrailing();
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) {
                inCodeFence = !inCodeFence;
                if (!inCodeFence) {
                    appendParagraph(blocks, paragraph);
                }
                continue;
            }
            if (inCodeFence) {
                if (paragraph.length() > 0) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
                continue;
            }
            Matcher heading = ATX_HEADING.matcher(line);
            if (heading.matches()) {
                appendParagraph(blocks, paragraph);
                flushTable(blocks, table);
                int level = heading.group(1).length();
                String title = heading.group(2).trim();
                if (!title.isEmpty()) {
                    blocks.add(context.heading(level, title, null));
                }
                continue;
            }
            if (TABLE_LINE.matcher(line).matches()) {
                appendParagraph(blocks, paragraph);
                if (table.length() > 0) {
                    table.append('\n');
                }
                table.append(line.trim());
                continue;
            }
            flushTable(blocks, table);
            if (line.isBlank()) {
                appendParagraph(blocks, paragraph);
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        appendParagraph(blocks, paragraph);
        flushTable(blocks, table);
        if (blocks.isEmpty()) {
            throw ApiException.parseFailed("Markdown 没有可提取文本");
        }
        return blocks;
    }

    private static void appendParagraph(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(Block.paragraph(text, null));
        }
    }

    private static void flushTable(List<Block> blocks, StringBuilder table) {
        if (table.length() == 0) {
            return;
        }
        String text = table.toString().trim();
        table.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(Block.table(text, null));
        }
    }
}
