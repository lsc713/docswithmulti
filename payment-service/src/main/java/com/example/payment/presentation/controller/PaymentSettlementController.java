package com.example.payment.presentation.controller;

import com.example.payment.application.usecase.PaymentSettlementQuery;
import com.example.payment.presentation.dto.PaymentSettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/payments/settlement?merchantId&from&to — 정산 대사 read-only 조회 (RECON-03).
 *
 * caller 인가 검사 없음(설계) — 크로스-가맹점 재무 노출은 배포 시 NetworkPolicy로 게이트웨이 파드에만
 * ingress를 허용해 차단한다(payment/product ingress 게이트와 동일 클래스, SettlementQueryController 미러).
 * 입력 검증(merchantId>0, from<to, ≤60일 윈도우)은 컨트롤러에서 수행(ASVS V5) — 질의 전에 400으로 거부.
 *
 * literal 경로 /v1/payments/settlement는 PaymentController의 /{paymentKey}/exists와 충돌하지 않는다.
 */
@RestController
@RequestMapping("/v1/payments/settlement")
@RequiredArgsConstructor
public class PaymentSettlementController {

    private static final Duration MAX_WINDOW = Duration.ofDays(60);

    private final PaymentSettlementQuery paymentSettlementQuery;

    @GetMapping
    public List<PaymentSettlementResponse> settlement(
        @RequestParam long merchantId,
        @RequestParam String from,
        @RequestParam String to
    ) {
        Instant fromTs = parseInstant(from, "from");
        Instant toTs = parseInstant(to, "to");
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        if (!fromTs.isBefore(toTs)) {
            throw new IllegalArgumentException("from must be strictly before to");
        }
        if (Duration.between(fromTs, toTs).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window must be <= 60 days");
        }

        return paymentSettlementQuery.query(merchantId, fromTs, toTs).stream()
            .map(PaymentSettlementResponse::from)
            .toList();
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant (e.g. 2026-08-04T00:00:00Z)");
        }
    }

    /**
     * 입력 검증 실패 → 400. 이 컨트롤러 로컬 핸들러(기존 GlobalExceptionHandler는 IllegalArgumentException을
     * 매핑하지 않음 — 그 파일은 PaymentSettlement allowlist 밖이라 수정 금지, 여기서 처리).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", e.getMessage()));
    }
}
