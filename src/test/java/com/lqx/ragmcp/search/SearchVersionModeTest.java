package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SearchVersionModeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void defaultModeForTodayAndFuture() {
        assertThat(SearchVersionMode.forRequest(TODAY, false, TODAY))
                .isEqualTo(SearchVersionMode.DEFAULT);
        assertThat(SearchVersionMode.forRequest(TODAY.plusDays(1), false, TODAY))
                .isEqualTo(SearchVersionMode.DEFAULT);
    }

    @Test
    void pastAsOfUsesHistoricalMode() {
        assertThat(SearchVersionMode.forRequest(LocalDate.of(2024, 6, 1), false, TODAY))
                .isEqualTo(SearchVersionMode.HISTORICAL);
    }

    @Test
    void includeSupersededAlwaysOverridesAsOf() {
        assertThat(SearchVersionMode.forRequest(TODAY, true, TODAY))
                .isEqualTo(SearchVersionMode.INCLUDE_SUPERSEDED);
        assertThat(SearchVersionMode.forRequest(LocalDate.of(2024, 6, 1), true, TODAY))
                .isEqualTo(SearchVersionMode.INCLUDE_SUPERSEDED);
    }
}
