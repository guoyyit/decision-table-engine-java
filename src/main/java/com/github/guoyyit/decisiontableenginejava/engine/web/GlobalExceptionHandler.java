package com.github.guoyyit.decisiontableenginejava.engine.web;

import com.github.guoyyit.decisiontableenginejava.engine.model.ForbiddenException;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleEngineException;
import com.github.guoyyit.decisiontableenginejava.engine.model.RuleSetValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常响应：越权 403、校验失败 400、其他规则引擎异常 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(RuleSetValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(RuleSetValidationException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RuleEngineException.class)
    public ResponseEntity<Map<String, Object>> handleRuleEngine(RuleEngineException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("error", status.getReasonPhrase());
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}
