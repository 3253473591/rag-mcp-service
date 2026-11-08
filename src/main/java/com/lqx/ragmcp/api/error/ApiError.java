package com.lqx.ragmcp.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Unified error response body: {code, message, traceId, details?}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String traceId, Map<String, Object> details) {

    public static ApiError of(ErrorCode code, String message, String traceId, Map<String, Object> details) {
        return new ApiError(code.code(), message, traceId,
                details == null || details.isEmpty() ? null : details);
    }
}
