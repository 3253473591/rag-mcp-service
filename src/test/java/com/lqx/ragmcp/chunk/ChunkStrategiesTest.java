package com.lqx.ragmcp.chunk;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkStrategiesTest {

    @Test
    void defaultsToHeading() {
        assertThat(ChunkStrategies.parse(null)).containsExactly(ChunkStrategy.HEADING);
        assertThat(ChunkStrategies.parse(List.of())).containsExactly(ChunkStrategy.HEADING);
        assertThat(ChunkStrategies.parse(List.of(" , "))).containsExactly(ChunkStrategy.HEADING);
    }

    @Test
    void parsesCommaSeparatedRepeatedAndUppercaseValues() {
        assertThat(ChunkStrategies.parse(List.of("FIXED,heading", "fixed")))
                .containsExactly(ChunkStrategy.FIXED, ChunkStrategy.HEADING);
        assertThat(ChunkStrategies.parse(List.of("heading", "HEADING")))
                .containsExactly(ChunkStrategy.HEADING);
    }

    @Test
    void rejectsUnknownStrategyWithValidationError() {
        assertThatThrownBy(() -> ChunkStrategies.parse(List.of("fixed,semantic")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION));
    }
}
