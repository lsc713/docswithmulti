package com.example.payment.infrastructure.http;

import com.example.payment.application.exception.PaymentAttemptException;
import com.example.payment.application.exception.PaymentApprovalRejectedException;
import com.example.payment.application.interfaces.TossPaymentPort;
import com.example.payment.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;
import com.example.payment.infrastructure.http.dto.TossPaymentResponse;

@Component
@Profile("!mock-pg")
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
            if (e.getStatusCode().is4xxClientError()) throw new PaymentApprovalRejectedException();
            throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
        } catch (RuntimeException e) {
            throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Status getStatus(String paymentKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(secretKey, "");
        try {
            TossPaymentResponse response = restTemplate.exchange(
                baseUrl + "/v1/payments/{paymentKey}", HttpMethod.GET,
                new HttpEntity<>(headers), TossPaymentResponse.class, paymentKey).getBody();
            if (response == null || response.status() == null) {
                throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
            }
            return switch (response.status()) {
                case "DONE" -> Status.DONE;
                case "ABORTED" -> Status.ABORTED;
                case "EXPIRED" -> Status.EXPIRED;
                case "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> Status.PENDING;
                default -> throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
            };
        } catch (PaymentAttemptException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PaymentAttemptException(ErrorCode.PG_SERVICE_UNAVAILABLE);
        }
    }
}
