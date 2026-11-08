package com.lqx.ragmcp.api.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes. See docs/02-api-contract.md section 5 and
 * docs/04-boundaries-and-errors.md section 11.
 */
public enum ErrorCode {

    VALIDATION("ERR-VALIDATION", HttpStatus.BAD_REQUEST),
    INVALID_AS_OF("ERR-INVALID_AS_OF", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE("ERR-FILE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_TYPE("ERR-UNSUPPORTED_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    PARSE_FAILED("ERR-PARSE_FAILED", HttpStatus.UNPROCESSABLE_ENTITY),
    DOC_NOT_FOUND("ERR-DOC_NOT_FOUND", HttpStatus.NOT_FOUND),
    EMBEDDING_UNAVAILABLE("ERR-EMBEDDING_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    EMBEDDING_TIMEOUT("ERR-EMBEDDING_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT),
    DB_UNAVAILABLE("ERR-DB_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL("ERR-INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus httpStatus;

    ErrorCode(String code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
