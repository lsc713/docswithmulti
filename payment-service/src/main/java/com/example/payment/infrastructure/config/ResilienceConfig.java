package com.example.payment.infrastructure.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j Circuit Breaker + Bulkhead 설정
 *
 * Circuit Breaker: 50% 실패율 초과 시 OPEN → 10초 후 HALF_OPEN → 성공 시 CLOSED.
 * Bulkhead (Semaphore): 동시 실행 수 제한으로 스레드 고갈 방지.
 *   - risk-management: 최대 30건 동시 호출
 *   - pg-cancel: 최대 50건 동시 호출
 *   - 톰캣 스레드 200개 중 나머지 120개는 다른 API에 보장
 */
@Configuration
public class ResilienceConfig {

    private static final io.github.resilience4j.circuitbreaker.CircuitBreakerConfig DEFAULT_CB_CONFIG =
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
            .slidingWindowType(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(DEFAULT_CB_CONFIG);
    }

    @Bean
    public CircuitBreaker riskManagementCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("risk-management");
    }

    @Bean
    public CircuitBreaker pgCancelCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("pg-cancel");
    }

    // ── Bulkhead (Semaphore) ──

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    public Bulkhead riskManagementBulkhead(BulkheadRegistry registry) {
        BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(30)
            .maxWaitDuration(Duration.ofMillis(500))
            .build();
        return registry.bulkhead("risk-management", config);
    }

    @Bean
    public Bulkhead pgCancelBulkhead(BulkheadRegistry registry) {
        BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(50)
            .maxWaitDuration(Duration.ofMillis(500))
            .build();
        return registry.bulkhead("pg-cancel", config);
    }
}
