package com.lqx.ragmcp.page;

import com.lqx.ragmcp.chunk.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic aggregation contract of the Phase 10 page layer: grouping by clause_path,
 * ordering, title/leaf-clause derivation, oversize splitting and the root fallback.
 */
class PageAggregatorTest {

    private final TokenCounter tokenCounter = new TokenCounter();
    private final PageAggregator aggregator = new PageAggregator(tokenCounter);

    @Test
    void groupsByClausePathInChunkIndexOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        // Input is intentionally out of order; aggregation must sort by chunk_index.
        List<PageSourceChunk> chunks = List.of(
                chunk(third, 2, "治疗内容", 30, "1.2", "第一章 > 1.2 治疗", 2, 2),
                chunk(first, 0, "诊断内容", 20, "1.1", "第一章 > 1.1 诊断", 1, 1),
                chunk(second, 1, "更多诊断", 10, "1.1", "第一章 > 1.1 诊断", 2, 2));

        List<PageDraft> pages = aggregator.aggregate("doc-key", "2025", "文档标题", chunks, 1200);

        assertThat(pages).hasSize(2);
        PageDraft diagnosis = pages.get(0);
        assertThat(diagnosis.pageKey()).isEqualTo("doc-key@2025#第一章 > 1.1 诊断");
        assertThat(diagnosis.clausePath()).isEqualTo("第一章 > 1.1 诊断");
        assertThat(diagnosis.leafClauseId()).isEqualTo("1.1");
        assertThat(diagnosis.title()).isEqualTo("诊断");
        assertThat(diagnosis.clauseIds()).containsExactly("1.1");
        assertThat(diagnosis.body()).isEqualTo("诊断内容\n\n更多诊断");
        assertThat(diagnosis.tokenCount()).isEqualTo(30);
        assertThat(diagnosis.pageNo()).isEqualTo(1);
        assertThat(diagnosis.pageEnd()).isEqualTo(2);
        assertThat(diagnosis.pageIndex()).isZero();
        assertThat(diagnosis.part()).isEqualTo(1);
        assertThat(diagnosis.sourceChunkIds()).containsExactly(first, second);

        PageDraft treatment = pages.get(1);
        assertThat(treatment.title()).isEqualTo("治疗");
        assertThat(treatment.pageIndex()).isEqualTo(1);
        assertThat(treatment.sourceChunkIds()).containsExactly(third);
        assertThat(treatment.embeddingText()).startsWith("治疗\n治疗内容");
    }

    @Test
    void chunksWithoutClausePathFormOneRootPage() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<PageSourceChunk> chunks = List.of(
                chunk(first, 0, "正文一", 10, null, null, 1, 1),
                chunk(second, 1, "正文二", 10, null, "   ", 2, 2));

        List<PageDraft> pages = aggregator.aggregate("doc-key", null, "总则文档", chunks, 1200);

        assertThat(pages).hasSize(1);
        PageDraft root = pages.get(0);
        assertThat(root.pageKey()).isEqualTo("doc-key@-#" + PageAggregator.ROOT_KEY);
        assertThat(root.clausePath()).isNull();
        assertThat(root.leafClauseId()).isNull();
        assertThat(root.clauseIds()).isEmpty();
        assertThat(root.title()).isEqualTo("总则文档");
        assertThat(root.body()).isEqualTo("正文一\n\n正文二");
        assertThat(root.sourceChunkIds()).containsExactly(first, second);
        assertThat(root.pageNo()).isEqualTo(1);
        assertThat(root.pageEnd()).isEqualTo(2);
    }

    @Test
    void titleStripsTheNumericClausePrefixAndKeepsFullWidthSpaces() {
        UUID id = UUID.randomUUID();
        List<PageSourceChunk> chunks = List.of(
                chunk(id, 0, "内容", 10, "4.2.2", "4  高血压诊断与评估 > 4.2  诊断标准 > 4.2.2\u3000诊断流程",
                        4, 4));

        PageDraft page = aggregator.aggregate("doc", "2025", "指南", chunks, 1200).get(0);

        assertThat(page.title()).isEqualTo("诊断流程");
        assertThat(page.leafClauseId()).isEqualTo("4.2.2");
        assertThat(page.clauseIds()).containsExactly("4.2.2");
    }

    @Test
    void distinctClauseIdsInsideOneGroupAreKeptInChunkOrder() {
        List<PageSourceChunk> chunks = List.of(
                chunk(UUID.randomUUID(), 0, "一", 10, "5.4.3.1", "5 > 5.4.3 > 5.4.3.1 A", 8, 8),
                chunk(UUID.randomUUID(), 1, "二", 10, "5.4.3.2", "5 > 5.4.3 > 5.4.3.1 A", 8, 8),
                chunk(UUID.randomUUID(), 2, "三", 10, "5.4.3.1", "5 > 5.4.3 > 5.4.3.1 A", 9, 9));

        PageDraft page = aggregator.aggregate("doc", "2025", "指南", chunks, 1200).get(0);

        assertThat(page.clauseIds()).containsExactly("5.4.3.1", "5.4.3.2");
        assertThat(page.leafClauseId()).isEqualTo("5.4.3.1");
        assertThat(page.pageEnd()).isEqualTo(9);
    }

    @Test
    void oversizeSectionIsSplitAtChunkBoundaries() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<PageSourceChunk> chunks = List.of(
                chunk(first, 0, "一", 30, "1.1", "1 > 1.1 小节", 1, 1),
                chunk(second, 1, "二", 30, "1.1", "1 > 1.1 小节", 2, 2),
                chunk(third, 2, "三", 30, "1.1", "1 > 1.1 小节", 3, 3));

        List<PageDraft> pages = aggregator.aggregate("doc", "2025", "文档", chunks, 50);

        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(PageDraft::part).containsExactly(1, 2, 3);
        assertThat(pages).extracting(PageDraft::pageIndex).containsExactly(0, 1, 2);
        assertThat(pages).allSatisfy(page -> {
            assertThat(page.pageKey()).isEqualTo("doc@2025#1 > 1.1 小节");
            assertThat(page.tokenCount()).isLessThanOrEqualTo(50);
            assertThat(page.sourceChunkIds()).hasSize(1);
        });
        assertThat(pages.get(0).sourceChunkIds()).containsExactly(first);
        assertThat(pages.get(1).sourceChunkIds()).containsExactly(second);
        assertThat(pages.get(2).sourceChunkIds()).containsExactly(third);
        assertThat(pages.get(1).pageNo()).isEqualTo(2);
        assertThat(pages.get(1).pageEnd()).isEqualTo(2);
    }

    @Test
    void singleChunkLargerThanTheLimitIsHardSplitByTokens() {
        String content = "这是一段用于验证单个超长块硬切分的中文内容。".repeat(30);
        int contentTokens = tokenCounter.count(content);
        int limit = Math.max(1, contentTokens / 4);
        UUID id = UUID.randomUUID();
        List<PageSourceChunk> chunks = List.of(
                chunk(id, 0, content, contentTokens, "9.1", "9 > 9.1 超长小节", 3, 4));

        List<PageDraft> pages = aggregator.aggregate("doc", "2025", "文档", chunks, limit);

        assertThat(pages).hasSizeGreaterThan(1);
        assertThat(pages.stream().mapToInt(PageDraft::tokenCount).sum())
                .isEqualTo(contentTokens);
        assertThat(pages).allSatisfy(page -> {
            assertThat(page.tokenCount()).isLessThanOrEqualTo(limit);
            assertThat(page.sourceChunkIds()).containsExactly(id);
            assertThat(page.pageNo()).isEqualTo(3);
            assertThat(page.pageEnd()).isEqualTo(4);
        });
    }

    @Test
    void aggregationIsDeterministicAndEmptyInputYieldsNoPages() {
        List<PageSourceChunk> chunks = List.of(
                chunk(UUID.randomUUID(), 1, "二", 10, "2", "2 第二节", 2, 2),
                chunk(UUID.randomUUID(), 0, "一", 10, "1", "1 第一节", 1, 1));

        List<PageDraft> first = aggregator.aggregate("doc", "2025", "文档", chunks, 1200);
        List<PageDraft> second = aggregator.aggregate("doc", "2025", "文档", chunks, 1200);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(PageDraft::title).containsExactly("第一节", "第二节");
        assertThat(aggregator.aggregate("doc", "2025", "文档", List.of(), 1200)).isEmpty();
    }

    private static PageSourceChunk chunk(UUID id, int index, String content, int tokens,
                                         String clauseId, String clausePath,
                                         Integer pageNo, Integer pageEnd) {
        return new PageSourceChunk(id, index, content, tokens, clauseId, clausePath, pageNo, pageEnd);
    }
}
