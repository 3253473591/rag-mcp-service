package com.lqx.ragmcp.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage for bearer parsing, fail-closed comparison and the audit fingerprint. */
class McpAuthTest {

    @Test
    void parsesBearerHeaderCaseInsensitivelyAndRejectsOtherSchemes() {
        assertThat(McpAuth.tokenFromHeader("Bearer abc")).isEqualTo("abc");
        assertThat(McpAuth.tokenFromHeader("bearer abc")).isEqualTo("abc");
        assertThat(McpAuth.tokenFromHeader("  Bearer   abc  ")).isEqualTo("abc");
        assertThat(McpAuth.tokenFromHeader(null)).isNull();
        assertThat(McpAuth.tokenFromHeader("Basic abc")).isNull();
        assertThat(McpAuth.tokenFromHeader("Bearer ")).isNull();
        assertThat(McpAuth.tokenFromHeader("Bearer")).isNull();
    }

    @Test
    void comparisonFailsClosedForUnsetOrWrongTokens() {
        assertThat(McpAuth.matches("secret", "secret")).isTrue();
        assertThat(McpAuth.matches("secret", "wrong")).isFalse();
        assertThat(McpAuth.matches("secret", null)).isFalse();
        assertThat(McpAuth.matches("", "anything")).isFalse();
        assertThat(McpAuth.matches(null, "anything")).isFalse();
    }

    @Test
    void fingerprintIsTheFirstTwelveHexCharsOfSha256() {
        String token = "unit-test-token";
        String expected = McpAuth.sha256Hex(token).substring(0, 12);

        assertThat(McpAuth.fingerprint(token)).isEqualTo(expected).hasSize(12);
        assertThat(McpAuth.fingerprint(token)).matches("[0-9a-f]{12}");
        assertThat(McpAuth.fingerprint(token)).doesNotContain(token);
        assertThat(McpAuth.fingerprint("")).isNull();
        assertThat(McpAuth.fingerprint(null)).isNull();
    }

    @Test
    void sha256MatchesKnownVector() {
        assertThat(McpAuth.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
