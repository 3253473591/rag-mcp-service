package com.lqx.ragmcp.search;

import com.lqx.ragmcp.api.dto.SearchRequest;
import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.chunk.ChunkStrategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-UNIT-VALID-01 + search-specific asOf/docIds/strategy validation. */
class SearchRequestValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void appliesContractDefaults() {
        SearchQuery query = SearchRequestValidator.validate(
                new SearchRequest(" 高血压 ", null, null, null, null, null, null, null, null), TODAY);

        assertThat(query.query()).isEqualTo("高血压");
        assertThat(query.topK()).isEqualTo(5);
        assertThat(query.docIds()).isEmpty();
        assertThat(query.version()).isNull();
        assertThat(query.asOf()).isEqualTo(TODAY);
        assertThat(query.includeSuperseded()).isFalse();
        assertThat(query.versionMode()).isEqualTo(SearchVersionMode.DEFAULT);
        assertThat(query.chunkStrategy()).isEqualTo(ChunkStrategy.HEADING);
        assertThat(query.strategy()).isEqualTo(SearchStrategy.HYBRID);
    }

    @Test
    void rejectsMissingOrBlankQuery() {
        assertValidationField(request(null), "query");
        assertValidationField(request("   "), "query");
        assertValidationField(request("x".repeat(501)), "query");
        assertThat(SearchRequestValidator.validate(request("x".repeat(500)), TODAY).query())
                .hasSize(500);
    }

    @Test
    void topKMustBeOneToTwenty() {
        assertValidationField(new SearchRequest("q", 0, null, null, null, null, null, null, null), "topK");
        assertValidationField(new SearchRequest("q", 21, null, null, null, null, null, null, null), "topK");
        assertThat(SearchRequestValidator.validate(
                new SearchRequest("q", 20, null, null, null, null, null, null, null), TODAY).topK())
                .isEqualTo(20);
    }

    @Test
    void docIdsMustBeAtMostFiftyValidUuidsAndAreDeduplicated() {
        UUID id = UUID.randomUUID();
        assertThat(SearchRequestValidator.validate(
                new SearchRequest("q", null, List.of(id.toString(), id.toString()), null,
                        null, null, null, null, null), TODAY).docIds())
                .containsExactly(id);

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        assertValidationField(new SearchRequest("q", null, tooMany, null,
                null, null, null, null, null), "docIds");

        assertValidationField(new SearchRequest("q", null, List.of("not-a-uuid"), null,
                null, null, null, null, null), "docIds");
        assertValidationField(new SearchRequest("q", null, List.of(" "), null,
                null, null, null, null, null), "docIds");
    }

    @Test
    void versionMustBeAtMostFiftyCharsAndBlankMeansNoFilter() {
        assertValidationField(requestWithVersion("v".repeat(51)), "version");
        assertThat(SearchRequestValidator.validate(requestWithVersion("  "), TODAY).version()).isNull();
        assertThat(SearchRequestValidator.validate(requestWithVersion("2025"), TODAY).version())
                .isEqualTo("2025");
    }

    @Test
    void invalidAsOfUsesDedicatedErrorCode() {
        assertAsOfError("2024-6-1");
        assertAsOfError("2024/06/01");
        assertAsOfError("abc");
        assertAsOfError("");
        assertAsOfError("1899-12-31");
        assertAsOfError("2101-01-01");
    }

    @Test
    void asOfDefaultsTodayAndBoundaryDatesAreAccepted() {
        assertThat(SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, null, null, null, null, null), TODAY).asOf())
                .isEqualTo(TODAY);
        assertThat(SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, "1900-01-01",
                        null, null, null, null), TODAY).asOf())
                .isEqualTo(LocalDate.of(1900, 1, 1));
        assertThat(SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, "2100-12-31",
                        null, null, null, null), TODAY).asOf())
                .isEqualTo(LocalDate.of(2100, 12, 31));
    }

    @Test
    void pastAsOfSelectsHistoricalMode() {
        SearchQuery query = SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, "2024-06-01",
                        null, null, null, null), TODAY);
        assertThat(query.versionMode()).isEqualTo(SearchVersionMode.HISTORICAL);

        SearchQuery included = SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, "2024-06-01",
                        true, null, null, null), TODAY);
        assertThat(included.versionMode()).isEqualTo(SearchVersionMode.INCLUDE_SUPERSEDED);
    }

    @Test
    void chunkStrategyOnlyAcceptsFixedAndHeading() {
        assertValidationField(new SearchRequest("q", null, null, null, null,
                null, "semantic", null, null), "chunkStrategy");
        assertThat(SearchRequestValidator.validate(new SearchRequest("q", null, null, null,
                null, null, "FIXED", null, null), TODAY).chunkStrategy())
                .isEqualTo(ChunkStrategy.FIXED);
    }

    @Test
    void strategyOnlyAcceptsHybridVectorKeyword() {
        assertValidationField(new SearchRequest("q", null, null, null, null,
                null, null, "bm25", null), "strategy");
        assertThat(SearchRequestValidator.validate(new SearchRequest("q", null, null, null,
                null, null, null, "KEYWORD", null), TODAY).strategy())
                .isEqualTo(SearchStrategy.KEYWORD);
    }

    @Test
    void nullBodyIsRejected() {
        assertThatThrownBy(() -> SearchRequestValidator.validate(null, TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }

    private SearchRequest request(String query) {
        return new SearchRequest(query, null, null, null, null, null, null, null, null);
    }

    private SearchRequest requestWithVersion(String version) {
        return new SearchRequest("q", null, null, version, null, null, null, null, null);
    }

    private void assertValidationField(SearchRequest request, String field) {
        assertThatThrownBy(() -> SearchRequestValidator.validate(request, TODAY))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION);
                    assertThat(ex.details()).containsEntry("field", field);
                });
    }

    private void assertAsOfError(String asOf) {
        assertThatThrownBy(() -> SearchRequestValidator.validate(
                new SearchRequest("q", null, null, null, asOf, null, null, null, null), TODAY))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_AS_OF));
    }
}
