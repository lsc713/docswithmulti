package com.example.product.infrastructure.http;

import com.example.product.application.interfaces.PaymentQueryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * payment-service GET /v1/payments/{paymentKey}/exists 조회 클라이언트 (RST-03, 03-04 계약).
 *
 * <p>plain RestTemplate — best-effort 배경 조회이므로 CircuitBreaker 없음. 조회 실패(4xx/5xx/타임아웃)는
 * 예외를 그대로 전파해 호출측(OrphanReservationRecoveryService)이 그 건을 skip하게 한다.
 * false로 강등해 삼키지 않는다 — 조회 불가를 "미존재"로 오인하면 정상 예약을 조기 release하는 오류(fail-safe).
 */
@Slf4j
@Component
public class PaymentQueryHttpClient implements PaymentQueryPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PaymentQueryHttpClient(
        RestTemplate restTemplate,
        @Value("${external.payment-service.url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** payment-service {exists:bool} 응답 바디. */
    public record ExistsResponse(boolean exists) {}

    @Override
    public boolean exists(String paymentKey) {
        String url = baseUrl + "/v1/payments/{paymentKey}/exists";
        ExistsResponse body = restTemplate.getForObject(url, ExistsResponse.class, paymentKey);
        if (body == null) {
            // 2xx인데 바디 없음 = 계약 위반. 미존재로 오인하지 않도록 예외 전파(skip 유도).
            throw new IllegalStateException("payment exists 응답 바디 없음: paymentKey=" + paymentKey);
        }
        return body.exists();
    }
}
