package com.lqx.ragmcp.search;

import com.lqx.ragmcp.config.RerankProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** T-UNIT-RERANK-01..06: deterministic rerank order, score and zero-weight equivalence. */
class CandidateRerankerTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);

    @Test
    void tUnitRerank01_absoluteCosineOutranksALargerRrfScore() {
        SearchCandidate keywordRescued = candidate(
                "00000000-0000-0000-0000-000000000001", "内容 A",
                "5.4.2", "第五章 > 5.4.2 药物治疗", 200);
        SearchCandidate vectorFirst = candidate(
                "00000000-0000-0000-0000-000000000002", "内容 B",
                "5.4.2", "第五章 > 5.4.2 药物治疗", 200);
        // A has both routes (higher RRF); B has the better cosine. Only the cosine weight is set.
        List<RrfFusion.ScoredCandidate> fused = List.of(
                new RrfFusion.ScoredCandidate(keywordRescued, 0.0325d),
                new RrfFusion.ScoredCandidate(vectorFirst, 0.0164d));
        Map<UUID, Double> cosine = Map.of(
                keywordRescued.chunkId(), 0.40d,
                vectorFirst.chunkId(), 0.90d);
        RerankProperties config = new RerankProperties();
        config.setWeightVectorScore(0.25d);
        config.setWeightTermCoverage(0.0d);
        config.setWeightTitleMatch(0.0d);
        config.setWeightClause(0.0d);
        config.setWeightLength(0.0d);

        List<CandidateReranker.Result> ordered = CandidateReranker.rerank(
                fused, cosine, List.of(), "", config, 2);

        assertThat(ordered).extracting(result -> result.candidate().chunkId())
                .containsExactly(vectorFirst.chunkId(), keywordRescued.chunkId());
        assertThat(ordered.get(0).rrfScore()).isCloseTo(0.0164d, within(1e-12));
        assertThat(ordered.get(0).rerankScore()).isCloseTo(0.0164d + 0.25d * 0.90d, within(1e-12));
        // The exposed score never changes meaning: it is still the raw RRF contribution.
        assertThat(ordered.get(1).rrfScore()).isCloseTo(0.0325d, within(1e-12));
    }

    @Test
    void tUnitRerank02_allWeightsZeroReproducesFusionOrderExactly() {
        List<RrfFusion.ScoredCandidate> fused = List.of(
                scored("00000000-0000-0000-0000-000000000011", 0.030d, "甲乙丙", "1.1"),
                scored("00000000-0000-0000-0000-000000000012", 0.020d, "甲乙", "1.2"),
                scored("00000000-0000-0000-0000-000000000013", 0.010d, "甲", null));
        RerankProperties config = zeroWeights();

        List<CandidateReranker.Result> ordered = CandidateReranker.rerank(
                fused, Map.of(), List.of("甲"), "甲", config, 3);

        assertThat(ordered).extracting(result -> result.candidate().chunkId())
                .containsExactlyElementsOf(fused.stream().map(sc -> sc.candidate().chunkId()).toList());
        for (int i = 0; i < fused.size(); i++) {
            assertThat(ordered.get(i).rerankScore())
                    .isCloseTo(fused.get(i).score(), within(1e-12));
        }
    }

    @Test
    void tUnitRerank03_emptyInputReturnsEmpty() {
        assertThat(CandidateReranker.rerank(List.of(), Map.of(), List.of(), "", new RerankProperties(), 20))
                .isEmpty();
        assertThat(CandidateReranker.rerank(null, null, null, null, new RerankProperties(), 20))
                .isEmpty();
    }

    @Test
    void tUnitRerank04_singleCandidateKeepsItsPositionAndExactScore() {
        SearchCandidate only = candidate("00000000-0000-0000-0000-000000000021",
                "预算控制要求：企业应当建立预算管理制度。", "5.2", "第五章 > 5.2 预算控制", 300);
        List<RrfFusion.ScoredCandidate> fused = List.of(new RrfFusion.ScoredCandidate(only, 0.0164d));
        RerankProperties config = new RerankProperties();
        Map<UUID, Double> cosine = Map.of(only.chunkId(), 0.80d);

        List<CandidateReranker.Result> ordered = CandidateReranker.rerank(
                fused, cosine, List.of("预算控制", "要求", "预算控制要求"), "预算控制要求", config, 20);

        assertThat(ordered).hasSize(1);
        CandidateReranker.Signals signals = CandidateReranker.signals(
                only, cosine, List.of("预算控制", "要求", "预算控制要求"), "预算控制要求",
                config.getMinContentChars());
        assertThat(signals.termCoverage()).isEqualTo(1.0d);
        assertThat(signals.vectorScore()).isEqualTo(0.80d);
        assertThat(ordered.get(0).rerankScore())
                .isCloseTo(CandidateReranker.score(0.0164d, signals, config), within(1e-12));
    }

    @Test
    void tUnitRerank05_rerankIsDeterministicAcrossRuns() {
        List<RrfFusion.ScoredCandidate> fused = List.of(
                scored("00000000-0000-0000-0000-000000000031", 0.032d, "预算控制", "5.2"),
                scored("00000000-0000-0000-0000-000000000032", 0.016d, "预算控制要求", "5.2"),
                scored("00000000-0000-0000-0000-000000000033", 0.010d, "其他", null));
        RerankProperties config = new RerankProperties();
        Map<UUID, Double> cosine = Map.of(
                fused.get(0).candidate().chunkId(), 0.61d,
                fused.get(1).candidate().chunkId(), 0.78d);

        List<CandidateReranker.Result> first = CandidateReranker.rerank(
                fused, cosine, List.of("预算控制", "要求"), "预算控制要求", config, 20);
        List<CandidateReranker.Result> second = CandidateReranker.rerank(
                fused, cosine, List.of("预算控制", "要求"), "预算控制要求", config, 20);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void tUnitRerank06_eachSignalCanPromoteOnItsOwn() {
        SearchCandidate plain = candidate("00000000-0000-0000-0000-000000000041",
                "短", null, null, 10);
        SearchCandidate rich = candidate("00000000-0000-0000-0000-000000000042",
                "预算控制要求：企业应当建立预算管理制度，明确预算编制、审批、执行和考核职责。",
                "5.2", "第五章 > 5.2 预算控制", 300);
        List<RrfFusion.ScoredCandidate> fused = List.of(
                new RrfFusion.ScoredCandidate(plain, 0.020d),
                new RrfFusion.ScoredCandidate(rich, 0.020d));

        assertThat(onlySignalMoves("term", fused, List.of("预算控制", "要求"), "预算控制要求"))
                .isEqualTo(rich.chunkId());
        assertThat(onlySignalMoves("title", fused, List.of(), "预算控制的要求"))
                .isEqualTo(rich.chunkId());
        assertThat(onlySignalMoves("clause", fused, List.of(), ""))
                .isEqualTo(rich.chunkId());
        assertThat(onlySignalMoves("length", fused, List.of(), ""))
                .isEqualTo(rich.chunkId());
    }

    @Test
    void tUnitRerank07_tailOutsideTheWindowKeepsFusionOrder() {
        List<RrfFusion.ScoredCandidate> fused = List.of(
                scored("00000000-0000-0000-0000-000000000051", 0.030d, "甲乙", "1.1"),
                scored("00000000-0000-0000-0000-000000000052", 0.020d, "甲", "1.2"),
                scored("00000000-0000-0000-0000-000000000053", 0.010d, "丙", "1.3"),
                scored("00000000-0000-0000-0000-000000000054", 0.005d, "丁", "1.4"));
        RerankProperties config = zeroWeights();
        config.setWeightVectorScore(1.0d);
        Map<UUID, Double> cosine = new LinkedHashMap<>();
        cosine.put(fused.get(0).candidate().chunkId(), 0.10d);
        cosine.put(fused.get(1).candidate().chunkId(), 0.90d);
        cosine.put(fused.get(2).candidate().chunkId(), 0.20d);
        cosine.put(fused.get(3).candidate().chunkId(), 0.99d);

        List<CandidateReranker.Result> ordered = CandidateReranker.rerank(
                fused, cosine, List.of(), "", config, 2);

        // Head (window=2) is reordered, the tail is untouched even though it has a higher cosine.
        assertThat(ordered).extracting(result -> result.candidate().chunkId()).containsExactly(
                fused.get(1).candidate().chunkId(),
                fused.get(0).candidate().chunkId(),
                fused.get(2).candidate().chunkId(),
                fused.get(3).candidate().chunkId());
    }

    @Test
    void tUnitRerank08_negativeWeightsAreTreatedAsZero() {
        SearchCandidate first = candidate("00000000-0000-0000-0000-000000000061",
                "甲", null, null, 10);
        SearchCandidate second = candidate("00000000-0000-0000-0000-000000000062",
                "乙", null, null, 10);
        List<RrfFusion.ScoredCandidate> fused = List.of(
                new RrfFusion.ScoredCandidate(first, 0.030d),
                new RrfFusion.ScoredCandidate(second, 0.010d));
        RerankProperties config = zeroWeights();
        config.setWeightVectorScore(-1.0d);

        List<CandidateReranker.Result> ordered = CandidateReranker.rerank(
                fused, Map.of(second.chunkId(), 1.0d), List.of(), "", config, 2);

        assertThat(ordered).extracting(result -> result.candidate().chunkId())
                .containsExactly(first.chunkId(), second.chunkId());
    }

    private static UUID onlySignalMoves(String signal, List<RrfFusion.ScoredCandidate> fused,
                                        List<String> terms, String normalizedQuery) {
        RerankProperties config = zeroWeights();
        switch (signal) {
            case "term" -> config.setWeightTermCoverage(0.01d);
            case "title" -> config.setWeightTitleMatch(0.01d);
            case "clause" -> config.setWeightClause(0.02d);
            case "length" -> config.setWeightLength(0.004d);
            default -> throw new IllegalArgumentException(signal);
        }
        return CandidateReranker.rerank(fused, Map.of(), terms, normalizedQuery, config, 2)
                .get(0).candidate().chunkId();
    }

    private static RerankProperties zeroWeights() {
        RerankProperties config = new RerankProperties();
        config.setWeightVectorScore(0.0d);
        config.setWeightTermCoverage(0.0d);
        config.setWeightTitleMatch(0.0d);
        config.setWeightClause(0.0d);
        config.setWeightLength(0.0d);
        return config;
    }

    private static RrfFusion.ScoredCandidate scored(String chunkId, double rrf,
                                                    String content, String clauseId) {
        String clausePath = clauseId == null ? null : "第五章 > " + clauseId;
        return new RrfFusion.ScoredCandidate(
                candidate(chunkId, content, clauseId, clausePath, content.length() * 10), rrf);
    }

    private static SearchCandidate candidate(String chunkId, String content, String clauseId,
                                             String clausePath, int pageNo) {
        return new SearchCandidate(
                UUID.fromString(chunkId),
                UUID.fromString("00000000-0000-0000-0000-00000000d001"),
                "doc",
                "2025",
                "ACTIVE",
                FROM,
                null,
                "标题",
                clauseId,
                clausePath,
                pageNo,
                pageNo,
                content,
                "heading",
                0,
                0.5d);
    }
}
