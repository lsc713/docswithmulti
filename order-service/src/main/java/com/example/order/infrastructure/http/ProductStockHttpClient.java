package com.example.order.infrastructure.http;

import com.example.order.application.interfaces.ProductStockClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
public class ProductStockHttpClient implements ProductStockClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public ProductStockHttpClient(@Value("${external.product-service.url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("product-stock");
    }

    @Override
    public void deduct(long skuId, int quantity) {
        try {
            circuitBreaker.executeRunnable(() -> {
                String url = baseUrl + "/internal/stocks/deduct";
                restTemplate.postForEntity(url, Map.of("skuId", skuId, "quantity", quantity), Void.class);
            });
        } catch (Exception e) {
            log.error("product-service 재고 차감 실패. skuId={}, quantity={}", skuId, quantity, e);
            throw new RuntimeException("재고 차감 실패: " + e.getMessage(), e);
        }
    }
}
