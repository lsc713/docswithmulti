package com.example.product.presentation;

import com.example.product.common.exception.BusinessException;
import com.example.product.common.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(Map.of(
                        "code", e.getErrorCode().getCode(),
                        "message", e.getMessage()
                ));
    }

    // T-01-02: reserve/seed 무제한·음수 qty 차단 — DTO Bean Validation 위반을 400으로 매핑
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.getDefaultMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_REQUEST.getHttpStatus())
                .body(Map.of(
                        "code", ErrorCode.INVALID_REQUEST.getCode(),
                        "message", detail
                ));
    }
}
