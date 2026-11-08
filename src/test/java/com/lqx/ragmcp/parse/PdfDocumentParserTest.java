package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.UploadProperties;
import com.lqx.ragmcp.support.TestDocuments;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser(new UploadProperties());

    @Test
    void tUnitParse01_extractsHeadingsPagesAndBodyText() {
        List<Block> blocks = parser.parse(TestDocuments.pdfWithHeadings(), "sample.pdf");

        assertThat(blocks).isNotEmpty();
        Block firstHeading = blocks.stream().filter(Block::isHeading).findFirst().orElseThrow();
        assertThat(firstHeading.text()).isEqualTo("3.2 Diagnosis criteria");
        assertThat(firstHeading.clauseId()).isEqualTo("3.2");
        assertThat(firstHeading.pageNo()).isEqualTo(1);

        Block secondHeading = blocks.stream().filter(Block::isHeading).skip(1).findFirst().orElseThrow();
        assertThat(secondHeading.text()).isEqualTo("3.3 Treatment principles");
        assertThat(secondHeading.pageNo()).isEqualTo(2);

        assertThat(blocks.stream().filter(block -> !block.isHeading()).toList())
                .anySatisfy(block -> {
                    assertThat(block.text()).contains("Blood pressure threshold");
                    assertThat(block.pageNo()).isEqualTo(1);
                });
    }

    @Test
    void tUnitParse01b_scannedPdfFailsWith422() {
        assertThatThrownBy(() -> parser.parse(TestDocuments.blankPdf(), "scan.pdf"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PARSE_FAILED));
    }

    @Test
    void corruptedPdfFailsWith422() {
        assertThatThrownBy(() -> parser.parse("not a pdf".getBytes(), "broken.pdf"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.PARSE_FAILED));
    }
}
