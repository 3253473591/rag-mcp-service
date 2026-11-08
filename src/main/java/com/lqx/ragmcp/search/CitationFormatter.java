package com.lqx.ragmcp.search;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Citation format (docs/02-api-contract.md 2.9):
 * {@code 《title》v{version}（{effectiveFrom} 生效） > {clausePath} > 第{pageNo}页}.
 * Missing version/clause/page segments are omitted; nothing is fabricated.
 */
public final class CitationFormatter {

    private CitationFormatter() {
    }

    public static String format(SearchCandidate candidate) {
        StringBuilder citation = new StringBuilder();
        citation.append('《').append(candidate.title()).append('》');

        String version = trimmedToNull(candidate.docVersion());
        if (version != null) {
            citation.append('v').append(version);
        }
        LocalDate effectiveFrom = candidate.effectiveFrom();
        if (effectiveFrom != null) {
            citation.append('（').append(effectiveFrom).append(" 生效）");
        }

        List<String> sections = new ArrayList<>(2);
        String clausePath = trimmedToNull(candidate.clausePath());
        if (clausePath != null) {
            sections.add(clausePath);
        }
        if (candidate.pageNo() != null) {
            sections.add("第" + candidate.pageNo() + "页");
        }
        for (String section : sections) {
            citation.append(" > ").append(section);
        }
        return citation.toString();
    }

    private static String trimmedToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
