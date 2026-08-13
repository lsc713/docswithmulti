package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.infrastructure.exception.ProductServiceException;
import com.example.payment.infrastructure.exception.StockInsufficientException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ProductStockHttpClient")
class ProductStockHttpClientTest {

    RestTemplate restTemplate;
    CircuitBreaker circuitBreaker;
    ProductStockHttpClient client;

    private static final List<ProductStockPort.Item> ITEMS =
        List.of(new ProductStockPort.Item(200L, 500L, 2));

    private static final ProductStockHttpClient.ReserveResponse RESERVE_RESPONSE =
        new ProductStockHttpClient.ReserveResponse(true, List.of(
            new ProductStockHttpClient.ItemResponse(
                500L, 200L, BigDecimal.valueOf(10_000), 2)));

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        // 테스트용 CB: 2건 중 50% 실패 시 즉시 OPEN (RiskManagementHttpClientTest 패턴)
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        circuitBreaker = registry.circuitBreaker("test-product");
        client = new ProductStockHttpClient(restTemplate, "http://product-service", circuitBreaker);
    }

    @Test
    @DisplayName("reserve 200 → 정상 반환(예외 없음)")
    void reserve_success() {
        when(restTemplate.postForEntity(
            anyString(), any(), eq(ProductStockHttpClient.ReserveResponse.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(RESERVE_RESPONSE));

        assertEquals(List.of(new ProductStockPort.ReservedItem(
            500L, 200L, BigDecimal.valueOf(10_000), 2)), client.reserve("pay_1", ITEMS));
    }

    @Test
    @DisplayName("reserve 409(STOCK_INSUFFICIENT) → StockInsufficientException")
    void reserve_conflict_throwsStockInsufficient() {
        when(restTemplate.postForEntity(
            anyString(), any(), eq(ProductStockHttpClient.ReserveResponse.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", null, null, null));

        assertThrows(StockInsufficientException.class, () -> client.reserve("pay_1", ITEMS));
    }

    @Test
    @DisplayName("reserve 5xx/비-2xx 응답 → ProductServiceException")
    void reserve_serverError_throwsProductServiceException() {
        when(restTemplate.postForEntity(
            anyString(), any(), eq(ProductStockHttpClient.ReserveResponse.class)))
            .thenReturn(org.springframework.http.ResponseEntity.status(500).build());

        assertThrows(ProductServiceException.class, () -> client.reserve("pay_1", ITEMS));
    }

    @Test
    @DisplayName("CircuitBreaker OPEN → CallNotPermittedException → ProductServiceException(fail-closed)")
    void reserve_circuitBreakerOpen_throwsProductServiceException() {
        when(restTemplate.postForEntity(
            anyString(), any(), eq(ProductStockHttpClient.ReserveResponse.class)))
            .thenThrow(new RuntimeException("connection refused"));

        // 2건 실패로 CB OPEN
        assertThrows(ProductServiceException.class, () -> client.reserve("pay_1", ITEMS));
        assertThrows(ProductServiceException.class, () -> client.reserve("pay_2", ITEMS));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // OPEN 상태 호출 → CallNotPermittedException 래핑 → ProductServiceException
        assertThrows(ProductServiceException.class, () -> client.reserve("pay_3", ITEMS));
        // 3번째 호출은 CB가 차단 → restTemplate 미실행
        verify(restTemplate, times(2)).postForEntity(
            anyString(), any(), eq(ProductStockHttpClient.ReserveResponse.class));
    }

    @Test
    @DisplayName("release 200 → 정상 반환")
    void release_success() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.release(
            "pay_1", List.of(new ProductStockPort.Item(500L, 2))));
    }
}
