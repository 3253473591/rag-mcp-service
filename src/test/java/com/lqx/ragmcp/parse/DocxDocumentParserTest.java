package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    void tUnitParse02b_extractsChineseArticleClauseIds() {
        List<Block> blocks = parser.parse(TestDocuments.docxWithChineseArticles(), "articles.docx");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.text()).startsWith("第五条");
            assertThat(block.clauseId()).isEqualTo("第五条");
            assertThat(block.clausePath()).contains("第一章 总 则").contains("第五条");
        });
    }

    @Test
    void tUnitParse02_extractsHeadingStylesAndTables() {
        List<Block> blocks = parser.parse(TestDocuments.docxWithHeadings(), "sample.docx");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.headingLevel()).isEqualTo(1);
            assertThat(block.text()).isEqualTo("1 Scope");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.headingLevel()).isEqualTo(2);
            assertThat(block.clausePath()).isEqualTo("1 Scope > 1.1 Definitions");
            assertThat(block.clauseId()).isEqualTo("1.1");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.table()).isTrue();
            assertThat(block.text()).contains("Leave type | Days").contains("Annual | 10");
        });
        assertThat(blocks).anySatisfy(block -> assertThat(block.text())
                .isEqualTo("This policy applies to all employees."));
        assertThat(blocks).allSatisfy(block -> assertThat(block.pageNo()).isNull());
    }
}
