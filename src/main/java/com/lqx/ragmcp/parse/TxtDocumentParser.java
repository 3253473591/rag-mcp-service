package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain text parsing. Per P-06 TXT has no headings: every paragraph is a body block and
 * clause_path stays null (the heading-aware chunker falls back to fixed windows).
 */
@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public SourceType sourceType() {
        return SourceType.TXT;
    }

    @Override
    public List<Block> parse(byte[] content, String basename) {
        String text = TextDecoder.decode(content);
        if (text == null) {
            throw ApiException.parseFailed("TXT 编码无法识别（仅支持 UTF-8 / GB18030）");
        }
        if (text.isBlank()) {
            throw ApiException.parseFailed("TXT 文件为空");
        }
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.stripTrailing();
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
        if (blocks.isEmpty()) {
            throw ApiException.parseFailed("TXT 没有可提取文本");
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
}
