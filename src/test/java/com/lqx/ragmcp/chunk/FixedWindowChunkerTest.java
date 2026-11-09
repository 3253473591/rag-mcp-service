package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.parse.Block;
import com.lqx.ragmcp.parse.HeadingContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowChunkerTest {

    private final TokenCounter counter = new TokenCounter();
    private final FixedWindowChunker chunker = new FixedWindowChunker(counter);

    @Test
    void tUnitChunk01_longTextIsSplitWithOverlap() {
        String text = "这是一段用于测试固定窗口切分的中文内容，包含足够多的汉字。".repeat(60);
        List<ChunkDraft> chunks = chunker.chunk(List.of(Block.paragraph(text, 1)));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(counter.count(chunk.content())).isLessThanOrEqualTo(FixedWindowChunker.TARGET_TOKENS);
        }
        String overlap = counter.tail(chunks.get(0).content(), FixedWindowChunker.OVERLAP_TOKENS).strip();
        assertThat(chunks.get(1).content()).startsWith(overlap);
    }

    @Test
    void tUnitChunk02_shortTextIsOneChunk() {
        String text = "很短的一段文本。";
        List<ChunkDraft> chunks = chunker.chunk(List.of(Block.paragraph(text, 1)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text);
        assertThat(chunks.get(0).pageNo()).isEqualTo(1);
    }

    @Test
    void tUnitChunk05_oversizedTableKeepsRowsAndRepeatsHeader() {
        StringBuilder table = new StringBuilder("类型 | 说明\n");
        for (int i = 0; i < 120; i++) {
            table.append("类型").append(i).append(" | 这是一行表格说明文字，用于制造超长表格内容。\n");
        }
        List<ChunkDraft> chunks = chunker.chunk(List.of(Block.table(table.toString(), 3)));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(chunk.table()).isTrue();
            assertThat(chunk.pageNo()).isEqualTo(3);
            assertThat(chunk.content().lines().findFirst().orElse("")).isEqualTo("类型 | 说明");
            assertThat(chunk.content()).doesNotContain("类型 | 说明\n类型 | 说明");
        }
        String joined = String.join("\n", chunks.stream().map(ChunkDraft::content).toList());
        assertThat(joined).contains("类型119");
    }

    @Test
    void tUnitChunk06_blankInputProducesNoChunks() {
        assertThat(chunker.chunk(List.of(Block.paragraph("   ", null)))).isEmpty();
        assertThat(chunker.chunk(List.of())).isEmpty();
    }

    @Test
    void fixedChunksInheritDominantClauseAndFullPageSpan() {
        HeadingContext context = new HeadingContext();
        List<Block> blocks = new ArrayList<>();
        blocks.add(context.heading(1, "1 诊断标准", 1));
        blocks.add(Block.paragraph("第一页诊断内容。".repeat(220), 1));
        blocks.add(context.heading(2, "2 治疗原则", 2));
        blocks.add(Block.paragraph("第二页治疗内容。".repeat(220), 2));

        List<ChunkDraft> chunks = chunker.chunk(blocks);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.clauseId()).isNotNull();
            assertThat(chunk.clausePath()).isNotNull();
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.pageNo()).isEqualTo(1);
            assertThat(chunk.pageEnd()).isEqualTo(2);
        });
    }

    @Test
    void tUnitChunk07_veryLongSentenceIsHardSplit() {
        String longSentence = "这是一个没有任何句号的超长句子".repeat(120);
        List<ChunkDraft> chunks = chunker.chunk(List.of(Block.paragraph(longSentence, 2)));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkDraft chunk : chunks) {
            assertThat(counter.count(chunk.content())).isLessThanOrEqualTo(FixedWindowChunker.TARGET_TOKENS);
        }
    }
}
