package com.lqx.ragmcp.eval;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The committed Phase 3 dataset shape and loader validation. */
class EvalDatasetTest {

    @Test
    void committedDatasetHas40ItemsWithFiveUnanswerableAndThreeDocuments() {
        EvalDataset dataset = EvalDataset.load(Path.of("eval/qa.jsonl"));

        assertThat(dataset.items()).hasSize(40);
        assertThat(dataset.answerableCount()).isEqualTo(35);
        assertThat(dataset.unanswerableCount()).isEqualTo(5);

        Set<String> ids = dataset.items().stream().map(EvalQaItem::id).collect(Collectors.toSet());
        assertThat(ids).hasSize(40);

        Set<String> docVersions = dataset.items().stream()
                .filter(EvalQaItem::answerable)
                .map(item -> item.gold().docKey() + "@" + item.gold().version())
                .collect(Collectors.toSet());
        assertThat(docVersions).hasSizeGreaterThanOrEqualTo(3)
                .contains("hypertension-guideline@2025", "hypertension-guideline@2024",
                        "enterprise-internal-control@2026");

        assertThat(dataset.items().stream()
                .filter(item -> item.answerable() && "2024".equals(
                        item.gold() == null ? null : item.gold().version())))
                .hasSize(7)
                .allSatisfy(item -> assertThat(item.asOf()).isEqualTo("2024-06-01"));

        assertThat(dataset.items().stream().filter(item -> !item.answerable()))
                .hasSize(5)
                .allSatisfy(item -> assertThat(item.gold()).isNull());
    }

    @Test
    void duplicateIdsAreRejected() {
        String jsonl = """
                {"id":"Q1","question":"q1","answerable":true,"gold":{"docKey":"d"}}
                {"id":"Q2","question":"q2","answerable":false}
                {"id":"Q1","question":"q1 again","answerable":false}
                """;

        assertThatThrownBy(() -> EvalDataset.load(new StringReader(jsonl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate QA id");
    }

    @Test
    void invalidPageRangeIsRejected() {
        String jsonl = """
                {"id":"Q1","question":"q","answerable":true,"gold":{"docKey":"d","pageRange":[5,2]}}
                """;

        assertThatThrownBy(() -> EvalDataset.load(new StringReader(jsonl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageRange");
    }

    @Test
    void malformedLineReportsLineNumber() {
        String jsonl = """
                {"id":"Q1","question":"ok","answerable":false}
                not-json
                """;

        assertThatThrownBy(() -> EvalDataset.load(new StringReader(jsonl)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void datasetPathIsCheckedIn() {
        assertThat(Files.exists(Path.of("eval/qa.jsonl"))).isTrue();
    }

    @Test
    void emptyDatasetIsRejected() {
        assertThatThrownBy(() -> EvalDataset.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }
}
