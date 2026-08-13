package com.example.payment.infrastructure.http;

import com.example.payment.application.exception.PaymentAttemptException;
import com.example.payment.application.interfaces.TossPaymentPort;
import com.example.payment.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class TossPaymentHttpClient implements TossPaymentPort {
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String secretKey;

    public TossPaymentHttpClient(
        @Qualifier("tossRestTemplate") RestTemplate restTemplate,
        @Value("${toss.base-url}") String baseUrl,
        @Value("${toss.secret-key}") String secretKey
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.secretKey = secretKey;
    }

    @Override
    public void confirm(String paymentKey, String paymentRequestId, BigDecimal amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(secretKey, "");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", paymentRequestId);
        try {
            restTemplate.exchange(
                baseUrl + "/v1/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                    "paymentKey", paymentKey,
                    "orderId", paymentRequestId,
                    "amount", amount.longValueExact()), headers),
                Map.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new PaymentAttemptException(
                ErrorCode.PG_SERVICE_UNAVAILABLE, "토스 결제 승인이 거절되었습니다.");
        } catch (RuntimeException e) {
            throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
        }
    }
}
