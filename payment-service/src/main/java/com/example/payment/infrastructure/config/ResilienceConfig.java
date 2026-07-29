package com.example.payment.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j Circuit Breaker 설정
 *
 * risk-management, PG 외부 연동 장애 전파를 막기 위한 Fail-closed 전략.
 * 50% 실패율 초과 시 OPEN → 10초 후 HALF_OPEN → 성공 시 CLOSED.
 */
@Configuration
public class ResilienceConfig {

    private static final io.github.resilience4j.circuitbreaker.CircuitBreakerConfig DEFAULT_CONFIG =
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .slidingWindowType(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(DEFAULT_CONFIG);
    }

    @Bean
    public CircuitBreaker riskManagementCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("risk-management");
    }

    @Bean
    public CircuitBreaker pgCancelCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("pg-cancel");
    }

    /**
     * WR-04: 조회성 호출(isCharged) 전용 CircuitBreaker.
     * validateAndReserve/compensate(쓰기·보상)와 분리해, 조회 호출의 일시적 실패율 증가가
     * 재무적으로 더 중요한 compensate 호출까지 함께 차단하지 않도록 한다.
     */
    @Bean
    public CircuitBreaker riskManagementReadCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("risk-management-read");
    }

    /**
     * WR-04: 조회성 호출(getStatus) 전용 CircuitBreaker. cancel(취소 실행)과 분리.
     */
    @Bean
    public CircuitBreaker pgCancelReadCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("pg-cancel-read");
    }
}
