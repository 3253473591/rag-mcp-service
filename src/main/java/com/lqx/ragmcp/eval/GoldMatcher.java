package com.lqx.ragmcp.eval;

import com.lqx.ragmcp.api.dto.SearchResultItem;

import java.util.List;
import java.util.Objects;

/**
 * Gold-hit matching for docs/11-retrieval-and-eval-spec.md 4.2.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li>{@code docKey} must be equal.</li>
 *   <li>When gold has a {@code clauseId}: a result that carries a clause id matches only when the
 *       normalized clause ids are equal. A result without a clause id (fixed-window chunks, and
 *       some heading chunks) falls back to {@code pageRange}.</li>
 *   <li>When gold has no {@code clauseId}: match by {@code pageRange} (or by docKey alone if the
 *       gold also has no page range).</li>
 * </ol>
 *
 * <p>The returned rank is the 1-based position of the first matching result. Multiple chunks from
 * the same document/answer therefore count once, and a later matching chunk is not skipped over a
 * non-matching one (that is what MRR measures).</p>
 */
public final class GoldMatcher {

    private GoldMatcher() {
    }

    /**
     * @return 1-based rank of the first matching result, or 0 when nothing matches.
     */
    public static int findRank(List<SearchResultItem> results, EvalGold gold) {
        if (results == null || results.isEmpty() || gold == null
                || gold.docKey() == null || gold.docKey().isBlank()) {
            return 0;
        }
        String goldDocKey = gold.docKey().trim();
        String goldClause = normalizeClause(gold.clauseId());
        boolean hasClause = goldClause != null;
        boolean hasPageRange = hasPageRange(gold.pageRange());

        int rank = 0;
        for (SearchResultItem result : results) {
            rank++;
            if (result == null || !goldDocKey.equals(trim(result.docKey()))) {
                continue;
            }
            if (hasClause) {
                String resultClause = normalizeClause(result.clauseId());
                if (resultClause != null) {
                    if (goldClause.equals(resultClause)) {
                        return rank;
                    }
                    // A different clause id is a genuine miss even if the page overlaps.
                    continue;
                }
                if (hasPageRange && pageInRange(result.pageNo(), gold.pageRange())) {
                    return rank;
                }
                continue;
            }
            if (hasPageRange) {
                if (pageInRange(result.pageNo(), gold.pageRange())) {
                    return rank;
                }
                continue;
            }
            // docKey-only gold: the first chunk of the right document counts.
            return rank;
        }
        return 0;
    }

    private static boolean hasPageRange(List<Integer> pageRange) {
        return pageRange != null && pageRange.size() == 2
                && pageRange.get(0) != null && pageRange.get(1) != null;
    }

    private static boolean pageInRange(Integer pageNo, List<Integer> pageRange) {
        if (pageNo == null) {
            return false;
        }
        return pageNo >= pageRange.get(0) && pageNo <= pageRange.get(1);
    }

    private static String normalizeClause(String clauseId) {
        String value = trim(clauseId);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** Convenience overload used by tests and diagnostics. */
    public static boolean matches(SearchResultItem result, EvalGold gold) {
        return findRank(List.of(Objects.requireNonNull(result)), gold) == 1;
    }
}
