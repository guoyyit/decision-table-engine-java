package com.github.guoyyit.decisiontableenginejava.web;

import com.github.guoyyit.decisiontableenginejava.engine.RuleValidator;
import com.github.guoyyit.decisiontableenginejava.eval.EvaluationException;
import com.github.guoyyit.decisiontableenginejava.service.PermissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常映射：越权 403、校验失败 422、规则/请求错误 400，评估内部异常 500 并带行列定位。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PermissionService.AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(PermissionService.AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("ACCESS_DENIED", e.getMessage(), 403));
    }

    @ExceptionHandler(RuleValidator.RuleValidationException.class)
    public ResponseEntity<ApiError> validation(RuleValidator.RuleValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("RULE_VALIDATION_FAILED", e.getMessage(), e.problems(), 422));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", e.getMessage(), 400));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> illegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("SERVICE_UNAVAILABLE", e.getMessage(), 503));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("MALFORMED_JSON", "请求体不是合法 JSON: "
                        + e.getMostSpecificCause().getMessage(), 400));
    }

    @ExceptionHandler(EvaluationException.class)
    public ResponseEntity<ApiError> evalError(EvaluationException e) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("tableId", e.tableId());
        location.put("rowId", e.rowId());
        location.put("columnIndex", e.columnIndex());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "EVALUATION_ERROR",
                "评估异常，定位: 表=" + e.tableId() + " 行=" + e.rowId()
                        + " 列#" + e.columnIndex() + "；" + e.getMessage(),
                java.util.List.of(location.toString()), 500));
    }
}
