package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.common.exception.infrastructure.RiskServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RiskManagementHttpClient")
class RiskManagementHttpClientTest {

    RestTemplate restTemplate;
    CircuitBreaker circuitBreaker;
    RiskManagementHttpClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        // 테스트용 CB: 2건 중 50% 실패 시 즉시 OPEN (빠른 테스트를 위해 slidingWindow=2)
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .build();
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("test-risk");
        client = new RiskManagementHttpClient(restTemplate, "http://risk-service", circuitBreaker);
    }

    @Test
    @DisplayName("정상 응답 → RiskReserveResult 반환")
    void validateAndReserve_success() {
        RiskReserveResult expected = new RiskReserveResult(1L,
            BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(30_000), BigDecimal.valueOf(970_000));
        when(restTemplate.postForEntity(anyString(), any(), eq(RiskReserveResult.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(expected));

        RiskReserveResult result = client.validateAndReserve(1L, 100L, BigDecimal.valueOf(30_000), LocalDate.now());

        assertEquals(expected, result);
    }

    @Test
    @DisplayName("HTTP 오류 응답 → RiskServiceException")
    void validateAndReserve_httpError_throwsRiskServiceException() {
        when(restTemplate.postForEntity(anyString(), any(), eq(RiskReserveResult.class)))
            .thenReturn(org.springframework.http.ResponseEntity.status(500).build());

        assertThrows(RiskServiceException.class,
            () -> client.validateAndReserve(1L, 100L, BigDecimal.valueOf(30_000), LocalDate.now()));
    }

    @Test
    @DisplayName("CB OPEN 후 호출 → CallNotPermittedException → RiskServiceException")
    void validateAndReserve_circuitBreakerOpen_throwsRiskServiceException() {
        // CB를 OPEN 상태로 만들기: 2건 연속 실패 (slidingWindow=2, threshold=50%)
        when(restTemplate.postForEntity(anyString(), any(), eq(RiskReserveResult.class)))
            .thenThrow(new RuntimeException("connection refused"));

        // 2건 실패로 CB OPEN
        assertThrows(RiskServiceException.class,
            () -> client.validateAndReserve(1L, 1L, BigDecimal.valueOf(1000), LocalDate.now()));
        assertThrows(RiskServiceException.class,
            () -> client.validateAndReserve(1L, 2L, BigDecimal.valueOf(1000), LocalDate.now()));

        // CB OPEN 상태 확인
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // CB OPEN 상태에서 호출 → CallNotPermittedException → RiskServiceException
        // (catch (Throwable t) 블록이 CallNotPermittedException을 RiskServiceException으로 래핑)
        assertThrows(RiskServiceException.class,
            () -> client.validateAndReserve(1L, 3L, BigDecimal.valueOf(1000), LocalDate.now()));

        // restTemplate은 3번째 호출에서는 실행되지 않음 (CB가 차단)
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), any());
    }
}
