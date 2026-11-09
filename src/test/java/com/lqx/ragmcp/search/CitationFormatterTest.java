package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationFormatterTest {

    @Test
    void tVersion07_includesVersionEffectiveDateClauseAndPage() {
        SearchCandidate candidate = candidate("2025", "第三章 > 3.2 诊断标准", 12,
                java.time.LocalDate.of(2025, 1, 1));

        assertThat(CitationFormatter.format(candidate))
                .isEqualTo("《国家基层高血压防治管理指南2025版》v2025（2025-01-01 生效）"
                        + " > 第三章 > 3.2 诊断标准 > 第12页");
    }

    @Test
    void omitsMissingVersionClauseAndPageWithoutFabricating() {
        SearchCandidate candidate = candidate(null, null, null, java.time.LocalDate.of(2025, 1, 1));

        assertThat(CitationFormatter.format(candidate))
                .isEqualTo("《国家基层高血压防治管理指南2025版》（2025-01-01 生效）");
    }

    @Test
    void blankVersionAndClauseAreOmitted() {
        SearchCandidate candidate = candidate("  ", "  ", null, null);

        assertThat(CitationFormatter.format(candidate)).isEqualTo("《国家基层高血压防治管理指南2025版》");
    }

    private SearchCandidate candidate(String version, String clausePath, Integer pageNo,
                                      java.time.LocalDate effectiveFrom) {
        return new SearchCandidate(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "hypertension-guideline",
                version,
                "ACTIVE",
                effectiveFrom,
                null,
                "国家基层高血压防治管理指南2025版",
                "3.2",
                clausePath,
                pageNo,
                pageNo,
                "内容",
                "heading",
                0,
                0.8);
    }
}
