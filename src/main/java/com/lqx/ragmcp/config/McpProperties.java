package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP transport credentials (Phase 5).
 *
 * <p>{@code app.mcp.auth-token} is bound from {@code MCP_AUTH_TOKEN}. It is deliberately
 * separate from {@code app.admin.token}. An unset/empty value does not prevent startup; every
 * {@code /mcp} request is rejected with HTTP 401 so a misconfigured server fails closed.</p>
 */
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    private String authToken = "";

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken == null ? "" : authToken;
    }
}
