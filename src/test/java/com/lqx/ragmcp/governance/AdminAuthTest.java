package com.lqx.ragmcp.governance;

import com.lqx.ragmcp.api.error.ApiException;
import com.lqx.ragmcp.api.error.ErrorCode;
import com.lqx.ragmcp.config.AdminProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-GOV-07 unit half: admin token comparison fails closed without leaking the token. */
class AdminAuthTest {

    @Test
    void acceptsMatchingToken() {
        AdminAuth auth = auth("test-admin");

        assertThatCode(() -> auth.requireAuthorized("test-admin")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingAndWrongToken() {
        AdminAuth auth = auth("test-admin");

        assertUnauthorized(() -> auth.requireAuthorized(null));
        assertUnauthorized(() -> auth.requireAuthorized(""));
        assertUnauthorized(() -> auth.requireAuthorized("test-admi"));
        assertUnauthorized(() -> auth.requireAuthorized("test-admin "));
        assertUnauthorized(() -> auth.requireAuthorized("TEST-ADMIN"));
    }

    @Test
    void rejectsEverythingWhenTokenIsNotConfigured() {
        assertUnauthorized(() -> auth("").requireAuthorized(""));
        assertUnauthorized(() -> auth("").requireAuthorized("any"));
        assertUnauthorized(() -> auth("   ").requireAuthorized("   "));
    }

    private static AdminAuth auth(String token) {
        AdminProperties properties = new AdminProperties();
        properties.setToken(token);
        return new AdminAuth(properties);
    }

    private static void assertUnauthorized(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.errorCode()).isEqualTo(ErrorCode.ADMIN_UNAUTHORIZED);
                    assertThat(api.getMessage()).doesNotContain("test-admin");
                });
    }
}
