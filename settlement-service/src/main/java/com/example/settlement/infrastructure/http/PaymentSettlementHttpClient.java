package com.example.settlement.infrastructure.http;

import com.example.settlement.application.interfaces.PaymentSettlementPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * payment-service GET /v1/payments/settlement 조회 클라이언트 (RECON-01, 03-01 계약).
 *
 * <p>plain RestTemplate — 주간 best-effort 배경 조회이므로 CircuitBreaker 없음. 조회 실패(4xx/5xx/타임아웃)는
 * 예외를 그대로 전파해 호출측(SettlementReconcileService)이 그 헤더를 skip 하게 한다 —
 * 빈 리스트로 강등하지 않는다(under-reconcile 방지, product PaymentQueryHttpClient fail-loud 동형).
 *
 * <p>wire 시각(createdAt/completedAt)은 String 으로 받아 Instant.parse — 컨슈머 관행 동형이며
 * plain RestTemplate 의 기본 ObjectMapper(JSR310 미등록)에 의존하지 않는다.
 */
@Slf4j
@Component
public class PaymentSettlementHttpClient implements PaymentSettlementPort {

    private static final ParameterizedTypeReference<List<PaymentSettlementResponse>> LIST_TYPE =
        new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PaymentSettlementHttpClient(
        RestTemplate restTemplate,
        @Value("${external.payment-service.url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** 03-01 wire 계약을 byte-for-byte 미러. 시각은 String(Z) 로 받아 매핑 시 Instant.parse. */
    public record PaymentSettlementResponse(
        String paymentKey,
        long merchantId,
        BigDecimal totalAmount,
        String status,
        String createdAt,
        List<Cancel> cancels
    ) {
        public record Cancel(long cancelRequestId, BigDecimal cancelAmount, String completedAt) {}
    }

    @Override
    public List<PaymentView> fetch(long merchantId, Instant from, Instant to) {
        String url = baseUrl + "/v1/payments/settlement?merchantId={m}&from={f}&to={t}";
        List<PaymentSettlementResponse> body = restTemplate.exchange(
            url, HttpMethod.GET, null, LIST_TYPE,
            merchantId, from.toString(), to.toString()).getBody();
        if (body == null) {
            throw new IllegalStateException(
                "payment settlement 응답 바디 없음: merchantId=" + merchantId + " from=" + from + " to=" + to);
        }
        return body.stream().map(PaymentSettlementHttpClient::toView).toList();
    }

    private static PaymentView toView(PaymentSettlementResponse r) {
        List<PaymentSettlementResponse.Cancel> cancels =
            r.cancels() == null ? List.of() : r.cancels();
        List<CancelView> views = cancels.stream()
            .map(c -> new CancelView(
                String.valueOf(c.cancelRequestId()), c.cancelAmount(), Instant.parse(c.completedAt())))
            .toList();
        return new PaymentView(
            r.paymentKey(), r.merchantId(), r.totalAmount(), r.status(),
            Instant.parse(r.createdAt()), views);
    }
}
