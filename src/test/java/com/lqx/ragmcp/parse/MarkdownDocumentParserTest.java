package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void tUnitParse03_nestedHeadingsKeepHierarchy() {
        List<Block> blocks = parser.parse(TestDocuments.markdown(), "handbook.md");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.headingLevel()).isEqualTo(1);
            assertThat(block.text()).isEqualTo("员工手册");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.text()).isEqualTo("1.1 年假");
            assertThat(block.clausePath()).isEqualTo("员工手册 > 1.1 年假");
            assertThat(block.clauseId()).isEqualTo("1.1");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.text()).isEqualTo("1.2.1 长期病假");
            assertThat(block.clausePath()).isEqualTo("员工手册 > 1.2 病假 > 1.2.1 长期病假");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.table()).isTrue();
            assertThat(block.text()).contains("| 年假 | 10 |");
        });
        assertThat(blocks).anySatisfy(block -> assertThat(block.text()).contains("提前申请"));
    }

    @Test
    void txtHasNoHeadings() {
        List<Block> blocks = new TxtDocumentParser().parse(TestDocuments.txt(), "policy.txt");

        assertThat(blocks).isNotEmpty();
        assertThat(blocks).noneMatch(Block::isHeading);
        assertThat(blocks).allSatisfy(block -> assertThat(block.clausePath()).isNull());
    }

    @Test
    void gb18030TextIsDecoded() {
        byte[] gb = "中文段落".getBytes(java.nio.charset.Charset.forName("GB18030"));
        List<Block> blocks = new TxtDocumentParser().parse(gb, "gb.txt");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("中文段落");
    }

    @Test
    void utf8BomIsStripped() {
        byte[] content = ("\uFEFF# 标题\n\n正文").getBytes(StandardCharsets.UTF_8);
        List<Block> blocks = new MarkdownDocumentParser().parse(content, "bom.md");

        assertThat(blocks).anySatisfy(block -> assertThat(block.text()).isEqualTo("标题"));
    }
}
