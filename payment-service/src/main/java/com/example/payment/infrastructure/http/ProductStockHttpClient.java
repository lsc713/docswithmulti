package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.infrastructure.exception.ProductServiceException;
import com.example.payment.infrastructure.exception.StockInsufficientException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * product-service 동기 재고 예약/해제 클라이언트 (D-P2-5).
 * RiskManagementHttpClient의 RestTemplate + Resilience4j CircuitBreaker + catch 계층 규율 복제.
 * 재고 부족(409)·장애(5xx/타임아웃/CB OPEN)는 예외 전파 → 결제 거부(fail-closed, D-P2-2).
 */
@Slf4j
@Component
public class ProductStockHttpClient implements ProductStockPort {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public ProductStockHttpClient(
        RestTemplate restTemplate,
        @Value("${external.product-service.url}") String baseUrl,
        CircuitBreaker productServiceCircuitBreaker
    ) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = productServiceCircuitBreaker;
    }

    @Override
    public void reserve(String paymentKey, List<Item> items) {
        post("/v1/stock/reserve", paymentKey, items);
    }

    @Override
    public void release(String paymentKey, List<Item> items) {
        post("/v1/stock/release", paymentKey, items);
    }

    private void post(String path, String paymentKey, List<Item> items) {
        try {
            circuitBreaker.executeCheckedSupplier(() -> {
                String url = baseUrl + path;
                Map<String, Object> body = Map.of(
                    "paymentKey", paymentKey,
                    "items", items.stream()
                        .map(i -> Map.of("skuId", i.skuId(), "qty", i.qty()))
                        .toList()
                );
                ResponseEntity<Void> response = restTemplate.postForEntity(url, body, Void.class);
                // 명시적 2xx 가드(RiskManagementHttpClient WR-03 규율): 커스텀 에러 핸들러 하에서
                // 2xx 아닌 응답이 예외 없이 반환돼도 성공으로 오인하지 않는다.
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new ProductServiceException("product-service 응답 오류: " + response.getStatusCode());
                }
                return response;
            });
        } catch (StockInsufficientException | ProductServiceException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            // 409 = 재고 부족(결제 거부). 그 외 4xx는 요청 결함 → 장애로 취급해 거부.
            if (e.getStatusCode().value() == 409) {
                throw new StockInsufficientException("재고 부족: " + e.getStatusCode(), e);
            }
            throw new ProductServiceException("product-service 요청 오류: " + e.getStatusCode(), e);
        } catch (Error e) {
            // OutOfMemoryError 등 Error는 정상 예외 흐름으로 위장하지 않는다.
            throw e;
        } catch (Throwable t) {
            // 5xx/타임아웃/CallNotPermittedException(CB OPEN) 포함 → 장애 = 거부(fail-closed).
            log.error("product-service {} 실패. paymentKey={}", path, paymentKey, t);
            throw new ProductServiceException("product-service 서비스 오류", t);
        }
    }
}
