package com.lqx.ragmcp.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reciprocal Rank Fusion with k=60:
 * {@code score = sum(1 / (60 + rank))}, rank starts at 1.
 *
 * <p>Deduplicates by chunkId. Equal scores are ordered by docId, chunkStrategy, chunkIndex,
 * then chunkId for deterministic pagination-independent output.</p>
 */
public final class RrfFusion {

    public static final int K = 60;

    private RrfFusion() {
    }

    public static List<ScoredCandidate> fuse(List<SearchCandidate> vector,
                                             List<SearchCandidate> keyword,
                                             int topK) {
        Map<UUID, Accumulator> byChunk = new LinkedHashMap<>();
        addRoute(byChunk, vector);
        addRoute(byChunk, keyword);

        Comparator<Accumulator> order = Comparator
                .comparingDouble(Accumulator::score).reversed()
                .thenComparing(acc -> acc.candidate().docId())
                .thenComparing(acc -> acc.candidate().chunkStrategy())
                .thenComparingInt(acc -> acc.candidate().chunkIndex())
                .thenComparing(acc -> acc.candidate().chunkId());

        return byChunk.values().stream()
                .sorted(order)
                .limit(Math.max(0, topK))
                .map(acc -> new ScoredCandidate(acc.candidate(), acc.score()))
                .toList();
    }

    private static void addRoute(Map<UUID, Accumulator> byChunk, List<SearchCandidate> route) {
        if (route == null || route.isEmpty()) {
            return;
        }
        Set<UUID> seenInRoute = new LinkedHashSet<>();
        int rank = 0;
        for (SearchCandidate candidate : route) {
            if (candidate == null || candidate.chunkId() == null) {
                continue;
            }
            if (!seenInRoute.add(candidate.chunkId())) {
                continue;
            }
            rank++;
            double contribution = 1.0d / (K + rank);
            Accumulator accumulator = byChunk.computeIfAbsent(candidate.chunkId(),
                    id -> new Accumulator(candidate));
            accumulator.add(contribution);
        }
    }

    public record ScoredCandidate(SearchCandidate candidate, double score) {
    }

    private static final class Accumulator {
        private final SearchCandidate candidate;
        private final List<Double> contributions = new ArrayList<>(2);

        private Accumulator(SearchCandidate candidate) {
            this.candidate = candidate;
        }

        private void add(double contribution) {
            contributions.add(contribution);
        }

        private double score() {
            return contributions.stream().mapToDouble(Double::doubleValue).sum();
        }

        private SearchCandidate candidate() {
            return candidate;
        }
    }
}
