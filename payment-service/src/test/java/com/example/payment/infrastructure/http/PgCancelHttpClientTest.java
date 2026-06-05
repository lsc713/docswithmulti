package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.common.exception.infrastructure.PgServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgCancelHttpClientTest {

    @Mock
    RestTemplate restTemplate;

    CircuitBreaker circuitBreaker;
    PgCancelHttpClient sut;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.ofDefaults("test");
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", circuitBreaker);
    }

    @Test
    void cancel_success() {
        PgCancelResult result = PgCancelResult.approved("tx-123");
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class)))
            .thenReturn(ResponseEntity.ok(result));

        assertThat(sut.cancel("key1", new BigDecimal("5000"), "환불")).isEqualTo(result);
    }

    @Test
    void cancel_throws_pg_service_exception_on_non_2xx() {
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class)))
            .thenReturn(ResponseEntity.status(500).build());

        assertThatThrownBy(() -> sut.cancel("key1", new BigDecimal("5000"), "환불"))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void cancel_throws_pg_service_exception_when_cb_open() {
        CircuitBreaker cb = CircuitBreaker.of("fast-open",
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100)
                .build());
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", cb);

        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class)))
            .thenThrow(new RuntimeException("connection error"));

        // trigger 2 failures to open CB
        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);
        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);

        // CB is now OPEN → next call should also throw PgServiceException
        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void get_status_throws_unsupported() {
        assertThatThrownBy(() -> sut.getStatus("key1"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
