package com.lqx.ragmcp.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The fallback is a system constant table, never a model-writable prompt option. */
class AnswerFallbackTest {

    private final AnswerFallback fallback = new AnswerFallback();

    @Test
    void nullEmptyAndBlankUpstreamTextUseTheReasonSpecificConstant() {
        assertThat(fallback.fallback(null, AbstainReason.NO_MATCH))
                .isEqualTo(AnswerFallback.NO_MATCH_MESSAGE);
        assertThat(fallback.fallback("", AbstainReason.NO_MATCH))
                .isEqualTo(AnswerFallback.NO_MATCH_MESSAGE);
        assertThat(fallback.fallback("   \t\n", AbstainReason.NO_MATCH))
                .isEqualTo(AnswerFallback.NO_MATCH_MESSAGE);

        assertThat(fallback.fallback(null, AbstainReason.TOO_NOISY))
                .isEqualTo(AnswerFallback.TOO_NOISY_MESSAGE);
        assertThat(fallback.fallback("", AbstainReason.TOO_NOISY))
                .isEqualTo(AnswerFallback.TOO_NOISY_MESSAGE);
        assertThat(fallback.fallback(" \n", AbstainReason.TOO_NOISY))
                .isEqualTo(AnswerFallback.TOO_NOISY_MESSAGE);
    }

    @Test
    void nonBlankUpstreamTextIsReturnedVerbatim() {
        String upstream = "  这是模型给出的回答，保留原始空格。 ";

        assertThat(fallback.fallback(upstream, AbstainReason.NO_MATCH)).isEqualTo(upstream);
        assertThat(fallback.fallback(upstream, AbstainReason.TOO_NOISY)).isEqualTo(upstream);
        assertThat(fallback.fallback(upstream, AbstainReason.NONE)).isEqualTo(upstream);
    }

    @Test
    void noneOrNullReasonUsesTheGenericConstantWhenUpstreamIsBlank() {
        assertThat(fallback.fallback(null, AbstainReason.NONE))
                .isEqualTo(AnswerFallback.GENERIC_EMPTY_MESSAGE);
        assertThat(fallback.fallback("  ", null))
                .isEqualTo(AnswerFallback.GENERIC_EMPTY_MESSAGE);
    }

    @Test
    void reasonSpecificMessagesAreDistinctAndNonBlank() {
        assertThat(AnswerFallback.NO_MATCH_MESSAGE).isNotBlank()
                .isNotEqualTo(AnswerFallback.TOO_NOISY_MESSAGE);
        assertThat(AnswerFallback.TOO_NOISY_MESSAGE).isNotBlank();
        assertThat(AnswerFallback.GENERIC_EMPTY_MESSAGE).isNotBlank();
    }
}
