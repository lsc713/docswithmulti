package com.example.payment.infrastructure.http;

import com.example.payment.infrastructure.exception.OrderVerifyRejectedException;
import com.example.payment.infrastructure.exception.OrderVerifyUnavailableException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderVerifyHttpClient — ProductStockHttpClientTest 미러(성공/404/409/403/5xx/CB OPEN, PLINK-01).
 */
@DisplayName("OrderVerifyHttpClient")
class OrderVerifyHttpClientTest {

    RestTemplate restTemplate;
    CircuitBreaker circuitBreaker;
    OrderVerifyHttpClient client;

    private static final List<Long> ORDER_ITEM_IDS = List.of(10L, 11L);

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        // 테스트용 CB: 2건 중 50% 실패 시 즉시 OPEN (ProductStockHttpClientTest 패턴)
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        circuitBreaker = registry.circuitBreaker("test-order");
        client = new OrderVerifyHttpClient(restTemplate, "http://order-service", circuitBreaker);
    }

    @Test
    @DisplayName("200 {orderId} → orderId 반환(예외 없음)")
    void verify_success_returnsOrderId() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                new OrderVerifyHttpClient.VerifyResponse(555L)));

        long orderId = client.verify(100L, ORDER_ITEM_IDS);

        assertEquals(555L, orderId);
    }

    @Test
    @DisplayName("404 ORDER_ITEM_NOT_FOUND → OrderVerifyRejectedException(404)")
    void verify_notFound_throwsRejected404() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        OrderVerifyRejectedException e = assertThrows(OrderVerifyRejectedException.class,
            () -> client.verify(100L, ORDER_ITEM_IDS));
        assertEquals(404, e.getErrorCode().getHttpStatus());
        assertEquals("ORDER_ITEM_NOT_FOUND", e.getErrorCode().getCode());
    }

    @Test
    @DisplayName("409 ORDER_ITEMS_MULTIPLE_ORDERS → OrderVerifyRejectedException(409)")
    void verify_conflict_throwsRejected409() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", null, null, null));

        OrderVerifyRejectedException e = assertThrows(OrderVerifyRejectedException.class,
            () -> client.verify(100L, ORDER_ITEM_IDS));
        assertEquals(409, e.getErrorCode().getHttpStatus());
        assertEquals("ORDER_ITEMS_MULTIPLE_ORDERS", e.getErrorCode().getCode());
    }

    @Test
    @DisplayName("403 ORDER_OWNERSHIP_MISMATCH → OrderVerifyRejectedException(403)")
    void verify_forbidden_throwsRejected403() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        OrderVerifyRejectedException e = assertThrows(OrderVerifyRejectedException.class,
            () -> client.verify(100L, ORDER_ITEM_IDS));
        assertEquals(403, e.getErrorCode().getHttpStatus());
        assertEquals("ORDER_OWNERSHIP_MISMATCH", e.getErrorCode().getCode());
    }

    @Test
    @DisplayName("5xx/비-2xx 응답 → OrderVerifyUnavailableException(fail-closed)")
    void verify_serverError_throwsUnavailable() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenReturn(org.springframework.http.ResponseEntity.status(500).build());

        assertThrows(OrderVerifyUnavailableException.class, () -> client.verify(100L, ORDER_ITEM_IDS));
    }

    @Test
    @DisplayName("CircuitBreaker OPEN → CallNotPermittedException → OrderVerifyUnavailableException(fail-closed)")
    void verify_circuitBreakerOpen_throwsUnavailable() {
        when(restTemplate.postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class)))
            .thenThrow(new RuntimeException("connection refused"));

        // 2건 실패로 CB OPEN
        assertThrows(OrderVerifyUnavailableException.class, () -> client.verify(100L, ORDER_ITEM_IDS));
        assertThrows(OrderVerifyUnavailableException.class, () -> client.verify(101L, ORDER_ITEM_IDS));
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // OPEN 상태 호출 → CallNotPermittedException 래핑 → OrderVerifyUnavailableException
        assertThrows(OrderVerifyUnavailableException.class, () -> client.verify(102L, ORDER_ITEM_IDS));
        // 3번째 호출은 CB가 차단 → restTemplate 미실행
        verify(restTemplate, times(2))
            .postForEntity(anyString(), any(), eq(OrderVerifyHttpClient.VerifyResponse.class));
    }
}
