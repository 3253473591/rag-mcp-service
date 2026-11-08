package com.lqx.ragmcp.eval;

import com.lqx.ragmcp.api.dto.SearchResultItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Gold matching rules from docs/11-retrieval-and-eval-spec.md 4.2. */
class GoldMatcherTest {

    @Test
    void clauseMatchReturnsFirstMatchingRank() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4));
        List<SearchResultItem> results = List.of(
                result("hypertension-guideline", "4.2", 4),
                result("hypertension-guideline", "4.2.1", 4));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(2);
    }

    @Test
    void wrongDocumentIsIgnored() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4));
        List<SearchResultItem> results = List.of(
                result("enterprise-internal-control", "4.2.1", 4),
                result("hypertension-guideline", "4.2.1", 4));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(2);
    }

    @Test
    void resultWithoutClauseIdFallsBackToPageRange() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4));
        List<SearchResultItem> results = List.of(
                result("hypertension-guideline", null, 4));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(1);
    }

    @Test
    void differentClauseIdDoesNotFallBackEvenWhenPageOverlaps() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4));
        List<SearchResultItem> results = List.of(
                result("hypertension-guideline", "4.2.2", 4),
                result("hypertension-guideline", "4.2.1", 4));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(2);
    }

    @Test
    void goldWithoutClauseUsesPageRange() {
        EvalGold gold = new EvalGold("enterprise-internal-control", "2026", null, List.of(2, 2));
        List<SearchResultItem> results = List.of(
                result("enterprise-internal-control", null, 1),
                result("enterprise-internal-control", null, 3),
                result("enterprise-internal-control", null, 2));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(3);
    }

    @Test
    void missingPageNumberCannotMatchPageRange() {
        EvalGold gold = new EvalGold("enterprise-internal-control", "2026", null, List.of(2, 2));
        List<SearchResultItem> results = List.of(result("enterprise-internal-control", null, null));

        assertThat(GoldMatcher.findRank(results, gold)).isZero();
    }

    @Test
    void multipleChunksFromSameAnswerCountOnce() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "5.2", List.of(5, 5));
        List<SearchResultItem> results = List.of(
                result("hypertension-guideline", "5.2", 5),
                result("hypertension-guideline", "5.2", 5));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(1);
    }

    @Test
    void docKeyOnlyGoldMatchesFirstChunkOfThatDocument() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", null, null);
        List<SearchResultItem> results = List.of(
                result("enterprise-internal-control", null, 1),
                result("hypertension-guideline", null, 7));

        assertThat(GoldMatcher.findRank(results, gold)).isEqualTo(2);
    }

    @Test
    void emptyOrNullInputsMiss() {
        EvalGold gold = new EvalGold("hypertension-guideline", "2025", "4.2.1", List.of(4, 4));
        assertThat(GoldMatcher.findRank(List.of(), gold)).isZero();
        assertThat(GoldMatcher.findRank(null, gold)).isZero();
        assertThat(GoldMatcher.findRank(List.of(result("hypertension-guideline", "4.2.1", 4)), null))
                .isZero();
    }

    static SearchResultItem result(String docKey, String clauseId, Integer pageNo) {
        return new SearchResultItem(
                "chunk-" + docKey + "-" + clauseId + "-" + pageNo,
                "doc-" + docKey,
                docKey,
                "2025",
                "ACTIVE",
                LocalDate.of(2025, 1, 1),
                null,
                "title",
                clauseId,
                clauseId == null ? null : "chapter > " + clauseId,
                pageNo,
                0.032,
                "content",
                "citation");
    }
}
