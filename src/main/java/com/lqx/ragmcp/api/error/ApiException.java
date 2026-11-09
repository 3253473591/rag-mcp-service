package com.lqx.ragmcp.api.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Business exception carrying a stable error code. The HTTP status is derived from
 * {@link ErrorCode}; messages must never contain stack traces, SQL or absolute paths.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyMap());
    }

    public ApiException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION, message);
    }

    public static ApiException validation(String message, Map<String, Object> details) {
        return new ApiException(ErrorCode.VALIDATION, message, details);
    }

    public static ApiException validationField(String field, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        return new ApiException(ErrorCode.VALIDATION, message, details);
    }

    public static ApiException invalidAsOf(String message) {
        return new ApiException(ErrorCode.INVALID_AS_OF, message);
    }

    public static ApiException fileTooLarge() {
        return new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过 20MB 上限");
    }

    public static ApiException unsupportedType(String extension) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("extension", extension == null ? "" : extension);
        return new ApiException(ErrorCode.UNSUPPORTED_TYPE,
                "不支持的文件类型，仅支持 .pdf/.docx/.md/.markdown/.txt", details);
    }

    public static ApiException parseFailed(String message) {
        return new ApiException(ErrorCode.PARSE_FAILED, message);
    }

    public static ApiException docNotFound(String docId) {
        return new ApiException(ErrorCode.DOC_NOT_FOUND, "文档不存在", Map.of("docId", docId));
    }

    public static ApiException embeddingUnavailable(String message) {
        return new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE, message);
    }

    public static ApiException embeddingTimeout(String message) {
        return new ApiException(ErrorCode.EMBEDDING_TIMEOUT, message);
    }

    public static ApiException dbUnavailable() {
        return new ApiException(ErrorCode.DB_UNAVAILABLE, "数据库暂时不可用，请稍后重试");
    }

    public static ApiException mcpUnauthorized() {
        return new ApiException(ErrorCode.MCP_UNAUTHORIZED, "MCP token 缺失或错误");
    }

    public static ApiException adminUnauthorized() {
        return new ApiException(ErrorCode.ADMIN_UNAUTHORIZED, "管理员 token 缺失或错误");
    }

    public static ApiException workItemNotFound(String workItemId) {
        return new ApiException(ErrorCode.WORKITEM_NOT_FOUND, "WorkItem 不存在",
                Map.of("workItemId", workItemId == null ? "" : workItemId));
    }

    public static ApiException approvalInvalid(String message) {
        return new ApiException(ErrorCode.APPROVAL_INVALID, message);
    }
}
