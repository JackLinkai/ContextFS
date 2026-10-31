package com.contextfs.api.common;

import com.contextfs.core.exception.ContextFsException;
import com.contextfs.core.exception.InvalidUriException;
import com.contextfs.core.exception.NodeNotFoundException;
import com.contextfs.core.exception.ScopeViolationException;
import com.contextfs.core.exception.TraceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 全局异常处理：把领域异常与请求错误映射为统一响应体。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidUriException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidUri(InvalidUriException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NodeNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNodeNotFound(NodeNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(TraceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleTraceNotFound(TraceNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ScopeViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleScopeViolation(ScopeViolationException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(error -> error.getField() + " " + error.getDefaultMessage())
                        .orElse("请求参数校验失败");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        String message = e.getMessage() == null ? "Bad request" : e.getMessage();
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ContextFsException.class)
    public ResponseEntity<ApiResponse<Void>> handleContextFs(ContextFsException e) {
        log.error("ContextFS error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(status.value(), message));
    }
}
