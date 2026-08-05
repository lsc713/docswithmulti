package com.example.settlement.presentation;

import com.example.settlement.application.exception.PayoutAlreadyExistsException;
import com.example.settlement.common.exception.BusinessException;
import com.example.settlement.domain.entity.Payout;
import com.example.settlement.presentation.dto.PayoutResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 409-return-existing: 더 구체적인 @ExceptionHandler 가 generic BusinessException 핸들러보다 우선하므로
     * 이 건만 {code,message} 대신 기존 payout 바디(PayoutResponse)를 실어 반환한다. 다른 BusinessException 은 그대로 generic.
     */
    @ExceptionHandler(PayoutAlreadyExistsException.class)
    public ResponseEntity<PayoutResponse> handlePayoutAlreadyExists(PayoutAlreadyExistsException e) {
        Payout existing = e.getExisting();
        log.warn("PayoutAlreadyExistsException: settlement={} payoutId={}",
            existing.getSettlementId(), existing.getId());
        return ResponseEntity.status(409)
            .body(new PayoutResponse(existing.getId(), existing.getStatus(), existing.getAmount()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(Map.of("code", e.getErrorCode().getCode(), "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", "INVALID_REQUEST", "message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("code", "INTERNAL_ERROR", "message", "서버 오류가 발생했습니다."));
    }
}
