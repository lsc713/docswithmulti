package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.OrderVerifyPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * order-service `POST /v1/orders/items:verify` 동기 검증 클라이언트 (PLINK-01/03).
 * ProductStockHttpClient의 RestTemplate + Resilience4j CircuitBreaker 구조 복제.
 * X-User-Id를 헤더로 포워딩(요청자 신원, T-02-02 소유 검증 위임).
 */
@Slf4j
@Component
public class OrderVerifyHttpClient implements OrderVerifyPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public OrderVerifyHttpClient(
        RestTemplate restTemplate,
        @Value("${external.order-service.url}") String baseUrl,
        CircuitBreaker orderServiceCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = orderServiceCircuitBreaker;
    }

    @Override
    public long verify(long userId, List<Long> orderItemIds) {
        try {
            return circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + "/v1/orders/items:verify";
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-User-Id", String.valueOf(userId));
                HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(Map.of("orderItemIds", orderItemIds), headers);

                ResponseEntity<VerifyResponse> response =
                    restTemplate.postForEntity(url, entity, VerifyResponse.class);
                // 명시적 2xx 가드(ProductStockHttpClient WR-03 규율): 커스텀 에러 핸들러 하에서
                // 2xx 아닌 응답이 예외 없이 반환돼도 성공으로 오인하지 않는다.
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new IllegalStateException("order-service 응답 오류: " + response.getStatusCode());
                }
                return response.getBody().orderId();
            });
        } catch (Error e) {
            // OutOfMemoryError 등 Error는 정상 예외 흐름으로 위장하지 않는다.
            throw e;
        } catch (Throwable t) {
            // Task 2에서 4xx/5xx/CB OPEN을 OrderVerifyRejectedException/OrderVerifyUnavailableException으로 세분화.
            log.error("order-service verify 실패. userId={}", userId, t);
            throw new IllegalStateException("order-service 서비스 오류", t);
        }
    }

    private record VerifyResponse(long orderId) {}
}
