package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** T-UNIT-RRF-01/02: raw RRF scores, de-duplication and deterministic tie-break. */
class RrfFusionTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);

    @Test
    void tUnitRrf01_twoRoutesUseRankStartingAtOneAndK60() {
        SearchCandidate a = candidate("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101", "heading", 0);
        SearchCandidate b = candidate("00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000102", "heading", 0);
        SearchCandidate c = candidate("00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000103", "heading", 0);

        List<RrfFusion.ScoredCandidate> fused = RrfFusion.fuse(
                List.of(b, a), List.of(b, c), 10);

        assertThat(fused).extracting(scored -> scored.candidate().chunkId())
                .containsExactly(b.chunkId(), a.chunkId(), c.chunkId());
        assertThat(fused.get(0).score()).isCloseTo(2.0d / 61.0d, within(1e-12));
        // Both A and C are rank 2 on one route; the docId tie-break puts A first.
        assertThat(fused.get(1).score()).isCloseTo(1.0d / 62.0d, within(1e-12));
        assertThat(fused.get(2).score()).isCloseTo(1.0d / 62.0d, within(1e-12));
    }

    @Test
    void tUnitRrf02_duplicateChunkInOneRouteContributesOnce() {
        SearchCandidate a = candidate("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101", "heading", 0);

        List<RrfFusion.ScoredCandidate> fused =
                RrfFusion.fuse(List.of(a, a), List.of(), 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isCloseTo(1.0d / 61.0d, within(1e-12));
    }

    @Test
    void sameScoreTieBreaksByDocIdThenStrategyThenChunkIndex() {
        SearchCandidate docLater = candidate("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000202", "heading", 0);
        SearchCandidate docEarlier = candidate("00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000101", "heading", 0);
        SearchCandidate fixed = candidate("00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000303", "fixed", 0);
        SearchCandidate heading = candidate("00000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000303", "heading", 0);
        SearchCandidate indexTwo = candidate("00000000-0000-0000-0000-000000000005",
                "00000000-0000-0000-0000-000000000404", "heading", 2);
        SearchCandidate indexOne = candidate("00000000-0000-0000-0000-000000000006",
                "00000000-0000-0000-0000-000000000404", "heading", 1);

        List<RrfFusion.ScoredCandidate> fused = RrfFusion.fuse(
                List.of(docLater, fixed, indexTwo), List.of(docEarlier, heading, indexOne), 10);

        assertThat(fused).extracting(scored -> scored.candidate().chunkId())
                .containsExactly(docEarlier.chunkId(), docLater.chunkId(),
                        fixed.chunkId(), heading.chunkId(),
                        indexOne.chunkId(), indexTwo.chunkId());
    }

    @Test
    void topKLimitsFinalResult() {
        SearchCandidate a = candidate("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000101", "heading", 0);
        SearchCandidate b = candidate("00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000102", "heading", 0);

        assertThat(RrfFusion.fuse(List.of(a, b), List.of(), 1)).hasSize(1);
        assertThat(RrfFusion.fuse(List.of(a, b), List.of(), 0)).isEmpty();
    }

    static SearchCandidate candidate(String chunkId, String docId, String strategy, int chunkIndex) {
        return new SearchCandidate(
                UUID.fromString(chunkId),
                UUID.fromString(docId),
                "doc-" + docId.substring(docId.length() - 3),
                "2025",
                "ACTIVE",
                FROM,
                null,
                "标题",
                "3.2",
                "第三章 > 3.2",
                12,
                12,
                "内容",
                strategy,
                chunkIndex,
                0.5);
    }
}
