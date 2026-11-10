package com.lqx.ragmcp.search;

import com.lqx.ragmcp.config.RerankProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic rerank stage (Phase 9). It re-orders the fused candidate head with absolute
 * signals that are already available in the request, so RRF rank information is no longer the
 * only ordering vote. No model, network call, clock or randomness participates: the same fused
 * list + query + configuration always produces the same order. See
 * {@link RerankProperties} for the score definition and {@code docs/acceptance/phase9-rerank.md}
 * for calibration.
 *
 * <p>The exposed {@code score} stays the raw RRF score; this class only produces the separate
 * {@code rerankScore} used for ordering.</p>
 */
public final class CandidateReranker {

    private CandidateReranker() {
    }

    /** One reranked candidate: the raw RRF score and the score that determined the order. */
    public record Result(SearchCandidate candidate, double rrfScore, double rerankScore) {
    }

    /** Normalized {@code [0,1]} features that make {@code rerankScore} reproducible by hand. */
    public record Signals(double vectorScore, double termCoverage, double titleMatch,
                          double clause, double length) {
    }

    /**
     * @param fused              candidates already ordered by the fusion stage (RRF, optionally
     *                           with the heading boost when rerank is disabled)
     * @param cosineByChunkId    chunkId -&gt; cosine similarity from the vector route; candidates
     *                           outside the vector route are absent (no semantic evidence)
     * @param queryTerms         {@link KeywordTerms#extract} of the query, recomputed in process
     * @param normalizedQuery    {@link SectionTitleMatcher#normalizeQuestion} of the query
     * @param config             weights and the short-chunk threshold
     * @param windowSize         how many leading candidates are re-ordered ({@code >= topK};
     *                           clamped to the list size)
     * @return a list of the same size; the first {@code windowSize} entries are sorted by
     *         {@code rerankScore} descending with the fusion order as a stable tie-break, the rest
     *         keep their fusion order
     */
    public static List<Result> rerank(List<RrfFusion.ScoredCandidate> fused,
                                      Map<UUID, Double> cosineByChunkId,
                                      List<String> queryTerms,
                                      String normalizedQuery,
                                      RerankProperties config,
                                      int windowSize) {
        if (fused == null || fused.isEmpty()) {
            return List.of();
        }
        int window = Math.min(fused.size(), Math.max(1, windowSize));
        List<String> terms = queryTerms == null ? List.of() : queryTerms;
        Map<UUID, Double> cosine = cosineByChunkId == null ? Map.of() : cosineByChunkId;

        List<Result> scored = new ArrayList<>(fused.size());
        for (RrfFusion.ScoredCandidate candidate : fused) {
            Signals signals = signals(candidate.candidate(), cosine, terms, normalizedQuery,
                    config.getMinContentChars());
            scored.add(new Result(candidate.candidate(), candidate.score(),
                    score(candidate.score(), signals, config)));
        }

        List<Result> head = new ArrayList<>(scored.subList(0, window));
        // List.sort is stable: equal rerankScore keeps the fusion order, which makes
        // "all weights zero" reproduce the input order exactly.
        head.sort(Comparator.comparingDouble(Result::rerankScore).reversed());
        List<Result> ordered = new ArrayList<>(scored.size());
        ordered.addAll(head);
        ordered.addAll(scored.subList(window, scored.size()));
        return ordered;
    }

    /** Recomputable score: raw RRF plus non-negative weighted features. */
    public static double score(double rrfScore, Signals signals, RerankProperties config) {
        return rrfScore
                + weight(config.getWeightVectorScore()) * signals.vectorScore()
                + weight(config.getWeightTermCoverage()) * signals.termCoverage()
                + weight(config.getWeightTitleMatch()) * signals.titleMatch()
                + weight(config.getWeightClause()) * signals.clause()
                + weight(config.getWeightLength()) * signals.length();
    }

    /** Extracts the normalized features for one candidate. Deterministic and side-effect free. */
    public static Signals signals(SearchCandidate candidate,
                                  Map<UUID, Double> cosineByChunkId,
                                  List<String> queryTerms,
                                  String normalizedQuery,
                                  int minContentChars) {
        String content = candidate.content() == null ? "" : candidate.content();
        Double cosine = candidate.chunkId() == null ? null : cosineByChunkId.get(candidate.chunkId());
        double vectorScore = cosine == null ? 0.0d : clamp(cosine);

        String haystack = content.toLowerCase(Locale.ROOT);
        List<String> terms = queryTerms == null ? List.of() : queryTerms;
        int matched = 0;
        for (String term : terms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                matched++;
            }
        }
        double termCoverage = terms.isEmpty() ? 0.0d : matched / (double) terms.size();

        double titleMatch = SectionTitleMatcher.boost(normalizedQuery, candidate.clausePath())
                / SectionTitleMatcher.MAX_BOOST;
        double clause = candidate.clauseId() != null && !candidate.clauseId().isBlank()
                ? 1.0d : 0.0d;
        double length = minContentChars <= 0
                ? 1.0d
                : Math.min(1.0d, content.length() / (double) minContentChars);
        return new Signals(vectorScore, termCoverage, Math.min(1.0d, titleMatch), clause, length);
    }

    private static double clamp(double value) {
        if (value <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, value);
    }

    /** A negative configured weight would make the rerank non-monotone; treat it as zero. */
    private static double weight(double configured) {
        return configured <= 0.0d ? 0.0d : configured;
    }
}
