package com.lqx.ragmcp.search;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Small, explainable heading signal on top of RRF (Phase 6c).
 *
 * <p>The keyword route matches the whole question text, so a natural-language question such as
 * {@code 高血压的诊断流程是什么？} does not contain a literal section title. This class removes
 * question noise ("是什么", "如何", "的", punctuation, ...) and gives a candidate a small additive
 * score when the <em>leaf</em> section title of its {@code clause_path} (for example
 * {@code 4.2.2  诊断流程}) occurs intact in the normalized query.</p>
 *
 * <p>The signal is deliberately narrow: it never looks at parent headings, long article-style
 * headings (enterprise clauses) or partial character overlaps. A query that names a section title
 * gets that section promoted; everything else keeps the plain RRF order.</p>
 */
public final class SectionTitleMatcher {

    /** Section titles shorter than this are too generic to be a reliable signal. */
    static final int MIN_TITLE_CHARS = 3;

    /** Longer leaf segments are article text (enterprise {@code 第X条 ...}), not section titles. */
    static final int MAX_TITLE_CHARS = 20;

    /** A 4-character title match adds 0.002, roughly the gap between adjacent RRF ranks. */
    static final double BOOST_PER_CHAR = 0.0005d;

    /** A single heading match never outweighs a full two-route contribution (~0.032). */
    static final double MAX_BOOST = 0.003d;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern LEADING_CLAUSE_NUMBER = Pattern.compile("^\\s*\\d+(\\.\\d+)*\\s*");

    private static final List<String> QUESTION_NOISE = List.of(
                    "是什么", "是多少", "有哪些", "哪几类", "哪两类", "哪些", "哪个",
                    "如何", "怎么", "怎样", "为什么", "为何", "什么时候", "多少", "什么",
                    "请问", "请", "的", "吗", "呢", "吧")
            .stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private SectionTitleMatcher() {
    }

    /** Removes punctuation/whitespace and common question words from a natural-language query. */
    public static String normalizeQuestion(String query) {
        String normalized = NON_WORD.matcher(query == null ? "" : query).replaceAll("");
        for (String noise : QUESTION_NOISE) {
            normalized = normalized.replace(noise, "");
        }
        return normalized;
    }

    /**
     * @return additive RRF boost in {@code [0, MAX_BOOST]}; zero when the leaf title is not
     *         literally named by the normalized query.
     */
    public static double boost(String normalizedQuery, String clausePath) {
        if (normalizedQuery == null || normalizedQuery.length() < MIN_TITLE_CHARS
                || clausePath == null || clausePath.isBlank()) {
            return 0.0d;
        }
        String leaf = leafTitle(clausePath);
        if (leaf.length() < MIN_TITLE_CHARS || leaf.length() > MAX_TITLE_CHARS) {
            return 0.0d;
        }
        if (!normalizedQuery.contains(leaf)) {
            return 0.0d;
        }
        return Math.min(MAX_BOOST, leaf.length() * BOOST_PER_CHAR);
    }

    private static String leafTitle(String clausePath) {
        int separator = clausePath.lastIndexOf('>');
        String leaf = separator >= 0 ? clausePath.substring(separator + 1) : clausePath;
        return LEADING_CLAUSE_NUMBER.matcher(leaf.trim()).replaceFirst("");
    }
}
