package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.infrastructure.exception.PgServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@org.springframework.context.annotation.Profile("!local")
public class PgCancelHttpClient implements PgCancelPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;
    // WR-04: 조회(getStatus) 전용 CircuitBreaker — cancel(취소 실행)과 분리해 조회 실패율 증가가
    // 취소 실행 호출까지 함께 차단하지 않도록 한다.
    private final CircuitBreaker readCircuitBreaker;

    public PgCancelHttpClient(
        RestTemplate restTemplate,
        @Value("${external.pg.url}") String baseUrl,
        CircuitBreaker pgCancelCircuitBreaker,
        CircuitBreaker pgCancelReadCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = pgCancelCircuitBreaker;
        this.readCircuitBreaker = pgCancelReadCircuitBreaker;
    }

    @Override
    public PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/payments/{paymentKey}/cancel";
                Map<String, Object> request = Map.of(
                    "cancelAmount", cancelAmount,
                    "cancelReason", cancelReason
                );
                ResponseEntity<PgCancelResult> response =
                    restTemplate.postForEntity(url, request, PgCancelResult.class, paymentKey);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new PgServiceException("PG 취소 응답 오류: " + response.getStatusCode());
                }
                return response.getBody();
            });
        } catch (PgServiceException e) {
            throw e;
        } catch (Error e) {
            // WR-05: OutOfMemoryError 등 Error까지 삼켜 정상 예외 흐름으로 위장하지 않는다.
            throw e;
        } catch (Throwable t) {
            log.error("PG cancel 실패. paymentKey={}", paymentKey, t);
            throw new PgServiceException("PG 서비스 오류", t);
        }
    }

    @Override
    public PgCancelResult getStatus(String paymentKey) {
        try {
            return readCircuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/payments/{paymentKey}/cancel/status";
                ResponseEntity<PgCancelResult> response =
                    restTemplate.getForEntity(url, PgCancelResult.class, paymentKey);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new PgServiceException("PG 상태조회 응답 오류: " + response.getStatusCode());
                }
                return response.getBody();
            });
        } catch (PgServiceException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            log.error("PG 상태조회 실패. paymentKey={}", paymentKey, t);
            throw new PgServiceException("PG 서비스 오류", t);
        }
    }
}
