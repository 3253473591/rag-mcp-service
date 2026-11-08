package com.lqx.ragmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Administrator API credentials.
 *
 * <p>{@code app.admin.token} is bound from {@code ADMIN_TOKEN}. An unset/empty value does not
 * prevent startup; all approve/deny calls are then rejected with 401 so a misconfigured server
 * fails closed.</p>
 */
@ConfigurationProperties(prefix = "app.admin")
public class AdminProperties {

    private String token = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }
}
