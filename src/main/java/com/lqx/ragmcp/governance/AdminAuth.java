package com.lqx.ragmcp.governance;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.config.AdminProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Validates the {@code X-Admin-Token} header for WorkItem approve/deny.
 *
 * <p>The comparison uses {@link MessageDigest#isEqual(byte[], byte[])} (constant-time, no
 * short-circuit), never {@code String.equals}. Token values are never logged. An empty configured
 * token fails closed: every request is rejected with 401.</p>
 */
@Component
public class AdminAuth {

    public static final String HEADER = "X-Admin-Token";
    public static final String PRINCIPAL = "admin";

    private final AdminProperties properties;

    public AdminAuth(AdminProperties properties) {
        this.properties = properties;
    }

    public void requireAuthorized(String suppliedToken) {
        String expectedToken = properties.getToken();
        if (expectedToken == null || expectedToken.isBlank()
                || suppliedToken == null || suppliedToken.isEmpty()) {
            throw ApiException.adminUnauthorized();
        }
        boolean matches = MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw ApiException.adminUnauthorized();
        }
    }
}
