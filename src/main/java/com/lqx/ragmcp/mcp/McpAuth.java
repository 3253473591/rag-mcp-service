package com.lqx.ragmcp.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bearer-token helpers for the MCP endpoint.
 *
 * <p>Comparisons use {@link MessageDigest#isEqual(byte[], byte[])} (constant-time, no
 * short-circuit) and never {@code String.equals}. Tokens are never logged. The audit table stores
 * only the first 12 hex characters of the token's SHA-256.</p>
 */
public final class McpAuth {

    public static final String HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private McpAuth() {
    }

    /**
     * Extracts the bearer token from an {@code Authorization} header value. Returns {@code null}
     * for a missing header, a non-Bearer scheme or an empty token. The scheme is case-insensitive
     * per RFC 6750.
     */
    public static String tokenFromHeader(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.length() <= BEARER_PREFIX.length()
                || !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** True only when a non-empty expected token is configured and equals the supplied token. */
    public static boolean matches(String expectedToken, String suppliedToken) {
        if (expectedToken == null || expectedToken.isBlank()
                || suppliedToken == null || suppliedToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 hex of the token, truncated to 12 characters; {@code null} for a blank token. */
    public static String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return sha256Hex(token).substring(0, 12);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
