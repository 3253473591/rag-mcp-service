package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.parse.Block;
import com.lqx.ragmcp.parse.HeadingContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeadingAwareChunkerTest {

    private final TokenCounter counter = new TokenCounter();
    private final HeadingAwareChunker chunker = new HeadingAwareChunker(counter);

    @Test
    void tUnitChunk03_hierarchyProducesCorrectClausePath() {
        HeadingContext context = new HeadingContext();
        List<Block> blocks = new ArrayList<>();
        blocks.add(context.heading(1, "第一章 总则", 1));
        blocks.add(Block.paragraph("本章规定目的和适用范围。", 1));
        blocks.add(context.heading(2, "1.1 目的", 1));
        blocks.add(Block.paragraph("为了规范管理，制定本制度。", 1));
        blocks.add(context.heading(2, "1.2 范围", 1));
        blocks.add(Block.paragraph("本制度适用于所有部门。", 1));
        blocks.add(context.heading(1, "第二章 管理", 2));
        blocks.add(Block.paragraph("管理部门负责执行。", 2));

        List<ChunkDraft> chunks = chunker.chunk(blocks);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.clausePath()).isEqualTo("第一章 总则 > 1.1 目的");
            assertThat(chunk.clauseId()).isEqualTo("1.1");
            assertThat(chunk.content()).isEqualTo("为了规范管理，制定本制度。");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.clausePath()).isEqualTo("第一章 总则 > 1.2 范围");
            assertThat(chunk.clauseId()).isEqualTo("1.2");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.clausePath()).isEqualTo("第二章 管理");
            assertThat(chunk.pageNo()).isEqualTo(2);
        });
        assertThat(chunks).noneMatch(chunk -> chunk.content().strip().equals("第一章 总则"));
    }

    @Test
    void tUnitChunk04_longSectionIsRecursivelySplit() {
        HeadingContext context = new HeadingContext();
        List<Block> blocks = new ArrayList<>();
        blocks.add(context.heading(1, "3 数据处理", 1));
        String body = "数据处理必须遵守安全规范，并进行审计记录。".repeat(100);
        blocks.add(Block.paragraph(body, 1));

        List<ChunkDraft> chunks = chunker.chunk(blocks);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(counter.count(chunk.content())).isLessThanOrEqualTo(FixedWindowChunker.TARGET_TOKENS);
            assertThat(chunk.clausePath()).isEqualTo("3 数据处理");
            assertThat(chunk.clauseId()).isEqualTo("3");
        }
    }

    @Test
    void shortSectionStaysInOneChunk() {
        HeadingContext context = new HeadingContext();
        List<Block> blocks = List.of(
                context.heading(1, "1 目的", 1),
                Block.paragraph("本制度用于测试。", 1),
                Block.paragraph("内容很短，不需要切分。", 1));

        List<ChunkDraft> chunks = chunker.chunk(blocks);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("本制度用于测试。").contains("内容很短，不需要切分。");
        assertThat(chunks.get(0).clausePath()).isEqualTo("1 目的");
    }

    @Test
    void noHeadingsFallsBackToFixedWindows() {
        String text = "没有标题的文本用于验证回退。".repeat(80);
        List<ChunkDraft> chunks = chunker.chunk(List.of(Block.paragraph(text, null)));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.clausePath()).isNull());
    }
}
