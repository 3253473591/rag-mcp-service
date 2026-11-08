package com.lqx.ragmcp.parse;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.chunk.ChunkDraft;
import com.lqx.ragmcp.chunk.HeadingAwareChunker;
import com.lqx.ragmcp.chunk.TokenCounter;
import com.lqx.ragmcp.config.UploadProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the committed fixtures under {@code src/test/resources/fixtures} so the
 * acceptance Gate 2 files cannot silently rot. They are format fixtures only, not part
 * of the 3-document evaluation corpus.
 */
class FormatFixturesTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    void docxFixtureHasStylesTableAndLongSection() {
        List<Block> blocks = new DocxDocumentParser().parse(fixture("sample.docx"), "sample.docx");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.headingLevel()).isEqualTo(1);
            assertThat(block.text()).isEqualTo("员工培训管理办法（测试样例）");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.headingLevel()).isEqualTo(2);
            assertThat(block.text()).isEqualTo("1 总则");
            assertThat(block.clauseId()).isEqualTo("1");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isHeading()).isTrue();
            assertThat(block.headingLevel()).isEqualTo(3);
            assertThat(block.text()).isEqualTo("1.1 目的");
            assertThat(block.clausePath()).isEqualTo("员工培训管理办法（测试样例） > 1 总则 > 1.1 目的");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.table()).isTrue();
            assertThat(block.text()).contains("培训类型 | 最低学时 | 适用对象")
                    .contains("入职培训 | 8 学时 | 新入职员工");
        });

        assertThat(sectionTokens(blocks, "3.1 培训记录与评估条款汇编"))
                .as("the long section must exceed the 900-token recursion threshold")
                .isGreaterThan(HeadingAwareChunker.MAX_SECTION_TOKENS);
        List<ChunkDraft> chunks = new HeadingAwareChunker(counter).chunk(blocks);
        String longSectionPath = "员工培训管理办法（测试样例） > 3 长文本与递归切分 > 3.1 培训记录与评估条款汇编";
        List<ChunkDraft> longSectionChunks = chunks.stream()
                .filter(chunk -> longSectionPath.equals(chunk.clausePath()))
                .toList();
        assertThat(longSectionChunks)
                .as("a >900-token section must be split into several chunks")
                .hasSizeGreaterThan(1);
        assertThat(longSectionChunks).allSatisfy(chunk -> {
            assertThat(chunk.clauseId()).isEqualTo("3.1");
            assertThat(chunk.clausePath()).isEqualTo(longSectionPath);
        });
    }

    @Test
    void markdownFixtureHasNestedHeadingsTableAndLongSection() {
        List<Block> blocks = new MarkdownDocumentParser().parse(fixture("sample.md"), "sample.md");

        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.headingLevel()).isEqualTo(1);
            assertThat(block.text()).isEqualTo("员工培训管理办法（测试样例）");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.headingLevel()).isEqualTo(3);
            assertThat(block.text()).isEqualTo("1.1 目的");
            assertThat(block.clausePath()).isEqualTo("员工培训管理办法（测试样例） > 1 总则 > 1.1 目的");
            assertThat(block.clauseId()).isEqualTo("1.1");
        });
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.table()).isTrue();
            assertThat(block.text()).contains("| 培训类型 | 最低学时 | 适用对象 |")
                    .contains("| 入职培训 | 8 学时 | 新入职员工 |");
        });
        assertThat(sectionTokens(blocks, "3.1 培训记录与评估条款汇编"))
                .isGreaterThan(HeadingAwareChunker.MAX_SECTION_TOKENS);
    }

    @Test
    void txtFixtureHasNoHeadingsAndFallsBackToFixedWindows() {
        List<Block> blocks = new TxtDocumentParser().parse(fixture("sample.txt"), "sample.txt");

        assertThat(blocks).isNotEmpty();
        assertThat(blocks).noneMatch(Block::isHeading);
        assertThat(blocks).allSatisfy(block -> assertThat(block.clausePath()).isNull());
        assertThat(blocks).anySatisfy(block -> assertThat(block.text()).contains("纯文本格式测试样例"));

        List<ChunkDraft> chunks = new HeadingAwareChunker(counter).chunk(blocks);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.clausePath()).isNull());
    }

    @Test
    void gb18030FixtureDecodesToTheSameParagraphs() {
        List<Block> utf8 = new TxtDocumentParser().parse(fixture("sample.txt"), "sample.txt");
        List<Block> gb = new TxtDocumentParser().parse(fixture("sample-gb18030.txt"), "sample-gb18030.txt");

        assertThat(TextDecoder.decode(fixture("sample-gb18030.txt"))).isNotNull();
        assertThat(gb).extracting(Block::text).containsExactlyElementsOf(utf8.stream().map(Block::text).toList());
    }

    @Test
    void scannedPdfFixtureFailsWithParseFailed() {
        PdfDocumentParser parser = new PdfDocumentParser(new UploadProperties());

        assertThatThrownBy(() -> parser.parse(fixture("sample-scanned.pdf"), "sample-scanned.pdf"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.PARSE_FAILED);
                    assertThat(ex.getMessage()).contains("扫描件");
                });
    }

    private int sectionTokens(List<Block> blocks, String headingText) {
        boolean inSection = false;
        int tokens = 0;
        for (Block block : blocks) {
            if (block.isHeading()) {
                if (inSection) {
                    break;
                }
                inSection = headingText.equals(block.text());
                continue;
            }
            if (inSection) {
                tokens += counter.count(block.text());
            }
        }
        return tokens;
    }

    private static byte[] fixture(String name) {
        try (InputStream in = Objects.requireNonNull(
                FormatFixturesTest.class.getClassLoader().getResourceAsStream("fixtures/" + name),
                "missing fixture: " + name)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read fixture: " + name, ex);
        }
    }

}
