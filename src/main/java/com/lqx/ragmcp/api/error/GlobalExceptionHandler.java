package com.lqx.ragmcp.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("request failed traceId={} code={} message={}", traceId(), code.code(), ex.getMessage());
        } else {
            log.info("request rejected traceId={} code={} message={}", traceId(), code.code(), ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, ex.getMessage(), traceId(), ex.details()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleMissingPart(Exception ex) {
        return badRequest("缺少必需参数: " + ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class, MultipartException.class})
    public ResponseEntity<ApiError> handleBadInput(Exception ex) {
        return badRequest("请求参数格式不正确");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        ErrorCode code = ErrorCode.FILE_TOO_LARGE;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "文件超过 20MB 上限", traceId(), null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        ErrorCode code = ErrorCode.UNSUPPORTED_TYPE;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "Content-Type 不受支持", traceId(), null));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException ex) {
        log.error("database failure traceId={}", traceId(), ex);
        ErrorCode code = ErrorCode.DB_UNAVAILABLE;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "数据库暂时不可用，请稍后重试", traceId(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unhandled exception traceId={}", traceId(), ex);
        ErrorCode code = ErrorCode.INTERNAL;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, "服务内部错误", traceId(), null));
    }

    private ResponseEntity<ApiError> badRequest(String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", message);
        ErrorCode code = ErrorCode.VALIDATION;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiError.of(code, message, traceId(), details));
    }

    private String traceId() {
        String current = MDC.get(TraceIdFilter.MDC_KEY);
        return current != null ? current : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
