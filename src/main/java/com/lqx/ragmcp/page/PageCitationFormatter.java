package com.lqx.ragmcp.page;

/**
 * Citation string for a page hit. Same shape as the chunk citation
 * ({@code 《document》v{version}（{effectiveFrom} 生效） > clause path > 第N页}) so a reader can
 * trace a page back to the document. Segments the page does not have are omitted, never fabricated.
 */
public final class PageCitationFormatter {

    private PageCitationFormatter() {
    }

    public static String format(PageCandidate page) {
        StringBuilder citation = new StringBuilder();
        citation.append('《').append(page.docTitle()).append('》');

        String version = trimmedToNull(page.docVersion());
        if (version != null) {
            citation.append('v').append(version);
        }
        if (page.effectiveFrom() != null) {
            citation.append('（').append(page.effectiveFrom()).append(" 生效）");
        }

        if (trimmedToNull(page.clausePath()) != null) {
            citation.append(" > ").append(page.clausePath().trim());
        }
        if (page.pageNo() != null) {
            citation.append(" > 第").append(page.pageNo());
            if (page.pageEnd() != null && page.pageEnd() > page.pageNo()) {
                citation.append('-').append(page.pageEnd());
            }
            citation.append("页");
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
