package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.infrastructure.exception.RiskServiceException;
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

    @Test
    @DisplayName("compensate: 2xx 응답 → 예외 없이 정상 완료")
    void compensate_success() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok().build());

        assertDoesNotThrow(() -> client.compensate(100L, 1L, BigDecimal.valueOf(30_000)));
    }

    @Test
    @DisplayName("compensate: WR-03 — RestTemplate 기본 에러 핸들러가 예외를 던지지 않는 2xx 아닌 응답도 RiskServiceException")
    void compensate_non2xxResponse_throwsRiskServiceException() {
        // 커스텀 ResponseErrorHandler 하에서는 2xx 아닌 상태코드가 예외 없이 ResponseEntity로
        // 반환될 수 있다 — WR-03이 방어하는 케이스(명시적 상태코드 가드 없으면 성공으로 오인됨).
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
            .thenReturn(org.springframework.http.ResponseEntity.status(409).build());

        assertThrows(RiskServiceException.class,
            () -> client.compensate(100L, 1L, BigDecimal.valueOf(30_000)));
    }

    @Test
    @DisplayName("isCharged: charged=true 응답 → true 반환")
    void isCharged_charged_true() {
        com.example.payment.application.dto.CheckChargeResponseDto response =
            new com.example.payment.application.dto.CheckChargeResponseDto("100", true, 1L, BigDecimal.valueOf(30_000));
        when(restTemplate.getForEntity(anyString(),
            eq(com.example.payment.application.dto.CheckChargeResponseDto.class), (Object[]) any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok(response));

        assertTrue(client.isCharged(100L));
    }

    @Test
    @DisplayName("isCharged: charged=false 응답 → false 반환")
    void isCharged_charged_false() {
        com.example.payment.application.dto.CheckChargeResponseDto response =
            new com.example.payment.application.dto.CheckChargeResponseDto("100", false, 1L, BigDecimal.valueOf(30_000));
        when(restTemplate.getForEntity(anyString(),
            eq(com.example.payment.application.dto.CheckChargeResponseDto.class), (Object[]) any()))
            .thenReturn(org.springframework.http.ResponseEntity.ok(response));

        assertFalse(client.isCharged(100L));
    }

    @Test
    @DisplayName("isCharged: HTTP 오류 응답 → RiskServiceException")
    void isCharged_httpError_throwsRiskServiceException() {
        when(restTemplate.getForEntity(anyString(),
            eq(com.example.payment.application.dto.CheckChargeResponseDto.class), (Object[]) any()))
            .thenReturn(org.springframework.http.ResponseEntity.status(500).build());

        assertThrows(RiskServiceException.class, () -> client.isCharged(100L));
    }

    @Test
    @DisplayName("WR-05: validateAndReserve 중 Error 발생 → RiskServiceException으로 감싸지 않고 그대로 전파")
    void validateAndReserve_error_propagatesUnwrapped() {
        when(restTemplate.postForEntity(anyString(), any(), eq(RiskReserveResult.class)))
            .thenThrow(new StackOverflowError("모의 Error"));

        assertThrows(StackOverflowError.class,
            () -> client.validateAndReserve(1L, 100L, BigDecimal.valueOf(30_000), LocalDate.now()));
    }

    @Test
    @DisplayName("isCharged: 네트워크 예외 → RiskServiceException")
    void isCharged_networkFailure_throwsRiskServiceException() {
        when(restTemplate.getForEntity(anyString(),
            eq(com.example.payment.application.dto.CheckChargeResponseDto.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));

        assertThrows(RiskServiceException.class, () -> client.isCharged(100L));
    }
}
