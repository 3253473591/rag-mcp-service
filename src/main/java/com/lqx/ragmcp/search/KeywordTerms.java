package com.lqx.ragmcp.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts lexical terms from a natural-language question for the keyword route
 * (docs/11-retrieval-and-eval-spec.md 3.2).
 *
 * <p><b>Why this exists.</b> The keyword route used to match the <em>whole</em> question text with
 * {@code ILIKE '%question%'}. A Chinese question such as {@code 高血压的诊断标准是什么？} never appears
 * verbatim inside a chunk, so the route returned 0 rows for all 40 eval questions and
 * {@code strategy=hybrid} silently degenerated into vector-only retrieval. Splitting the question
 * into terms gives the OR of several short patterns, which is what makes the lexical route real.</p>
 *
 * <p><b>What it is not.</b> This is not a tokenizer: there is no dictionary, no POS tagger and no
 * {@code zhparser}/{@code pg_jieba} extension involved (neither is available in the pgvector image).
 * It removes question noise and a small set of particles used as separators, then keeps the
 * remaining CJK runs and ASCII words. Term-level noise is bounded by {@code keyword-max-terms} and
 * by the repository ranking (match count, then trigram similarity), not by precision here.</p>
 *
 * <p>Deterministic and side-effect free; the returned list preserves first-seen order.</p>
 */
public final class KeywordTerms {

    /** CJK terms shorter than this are too generic to be a useful lexical signal. */
    static final int MIN_CJK_CHARS = 2;

    /** ASCII terms shorter than this are usually stopwords ("in", "is", "of"). */
    static final int MIN_ASCII_CHARS = 3;

    /** Longer runs are article text or identifiers, not query terms. */
    static final int MAX_TERM_CHARS = 12;

    /** Question noise, longest first so {@code 是什么} is removed before {@code 什么}. */
    private static final List<String> CJK_NOISE = List.of(
            "是什么", "是多少", "有哪些", "哪几类", "哪两类", "什么时候", "为什么",
            "哪些", "哪个", "多少", "什么", "如何", "怎么", "怎样", "为何", "请问", "请",
            "吗", "呢", "吧");

    /**
     * Particles used as separators. Deliberately does not include {@code 有}/{@code 中}/{@code 时}/
     * {@code 里}: splitting on them corrupts real words ({@code 有效}, {@code 中国}, {@code 小时},
     * {@code 公里}) far more often than it helps.
     */
    private static final List<String> CJK_SEPARATORS = List.of(
            "可以", "包括", "进行", "需要", "应该", "是否", "以及",
            "的", "是", "了", "和", "与", "及", "或", "把", "被", "对", "在", "为");

    /** Connector characters stripped only at term edges, never inside a word. */
    private static final String EDGE_CONNECTORS = "有是在为的了和与及或把被对";

    private static final Set<String> EN_STOPWORDS = Set.of(
            "what", "which", "how", "why", "when", "where", "who", "whose", "whom",
            "is", "are", "was", "were", "be", "been", "being",
            "the", "a", "an", "of", "for", "to", "in", "on", "at", "by", "with",
            "and", "or", "not", "no", "do", "does", "did", "can", "could", "should",
            "would", "will", "shall", "may", "might", "must", "from", "that", "this",
            "these", "those", "it", "its", "as", "if", "than", "then", "there", "their");

    private KeywordTerms() {
    }

    /**
     * @param query    already length-capped question text ({@code keyword-max-chars})
     * @param maxTerms hard cap, so a long question cannot explode the SQL
     * @return terms in first-seen order; empty when nothing usable remains
     */
    public static List<String> extract(String query, int maxTerms) {
        if (query == null || query.isBlank() || maxTerms <= 0) {
            return List.of();
        }
        String marked = mark(query);
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : marked.split("\\n+")) {
            addTerm(terms, raw);
        }
        // Keep the cleaned whole question as a trailing term: when the exact phrase exists in a
        // chunk this is the most precise signal, and when it does not the pattern simply misses.
        String whole = stripClutter(marked);
        addTerm(terms, whole);
        List<String> capped = new ArrayList<>(Math.min(terms.size(), maxTerms));
        for (String term : terms) {
            if (capped.size() >= maxTerms) {
                break;
            }
            capped.add(term);
        }
        return List.copyOf(capped);
    }

    /** Replaces noise words and separators with {@code \n} so the text can be split. */
    private static String mark(String query) {
        String text = query;
        for (String noise : CJK_NOISE) {
            text = text.replace(noise, "\n");
        }
        for (String separator : CJK_SEPARATORS) {
            text = text.replace(separator, "\n");
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // Punctuation and whitespace also split, otherwise an English question would stay one
            // long token ("What is the blood pressure...") and get dropped by MAX_TERM_CHARS.
            // '%' and '_' are deliberately NOT delimiters: 100%_ must reach the escaper intact.
            builder.append(isDelimiter(ch) ? '\n' : ch);
        }
        return builder.toString();
    }

    private static boolean isDelimiter(char ch) {
        if (Character.isWhitespace(ch)) {
            return true;
        }
        return "，,。、；;：:？?！!（）()【】[]“”\"'·…—/\\|".indexOf(ch) >= 0;
    }

    /** Removes punctuation/whitespace left over from the original question. */
    private static String stripClutter(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean alnum = Character.isLetterOrDigit(ch);
            // ASCII letters/digits and CJK ideographs are kept; everything else (punctuation,
            // full-width variants, symbols) is dropped. '%' and '_' are kept on purpose: a query
            // such as 100%_ must still be escaped and matched literally.
            boolean wildcard = ch == '%' || ch == '_';
            if (alnum || wildcard) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static void addTerm(Set<String> terms, String candidate) {
        if (candidate == null) {
            return;
        }
        String term = trimEdges(stripClutter(candidate.replace("\n", "")));
        if (term.isEmpty() || term.length() > MAX_TERM_CHARS) {
            return;
        }
        if (isAsciiOnly(term)) {
            String normalized = term.toLowerCase(Locale.ROOT);
            if (normalized.length() < MIN_ASCII_CHARS || EN_STOPWORDS.contains(normalized)) {
                return;
            }
            terms.add(normalized);
            return;
        }
        if (term.length() < MIN_CJK_CHARS) {
            return;
        }
        terms.add(term);
    }

    /**
     * Strips connector characters at the <em>trailing</em> edge only. Splitting is not enough: a
     * question such as {@code 公司年假有多少天} leaves {@code 公司年假有} after noise removal, and the
     * trailing {@code 有} would make the term miss. Leading characters are never stripped because a
     * real word may start with one ({@code 有效}, {@code 对策}), and interior characters are never
     * touched either.
     */
    private static String trimEdges(String term) {
        int end = term.length();
        while (end > 0 && EDGE_CONNECTORS.indexOf(term.charAt(end - 1)) >= 0) {
            end--;
        }
        return term.substring(0, end);
    }

    private static boolean isAsciiOnly(String term) {
        for (int i = 0; i < term.length(); i++) {
            if (term.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
