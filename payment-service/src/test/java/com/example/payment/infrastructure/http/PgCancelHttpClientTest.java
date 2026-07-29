package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.infrastructure.exception.PgServiceException;
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
    CircuitBreaker readCircuitBreaker;
    PgCancelHttpClient sut;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreaker.ofDefaults("test");
        // WR-04: getStatus(조회) 전용 CB — cancel(취소 실행)과 분리
        readCircuitBreaker = CircuitBreaker.ofDefaults("test-read");
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", circuitBreaker, readCircuitBreaker);
    }

    // postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables)
    // varargs 매칭: anyString(), any(), eq(Class), (Object[]) any()
    @Test
    void cancel_success() {
        PgCancelResult result = PgCancelResult.approved("tx-123");
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(result));

        assertThat(sut.cancel("key1", new BigDecimal("5000"), "환불")).isEqualTo(result);
    }

    @Test
    void cancel_throws_pg_service_exception_on_non_2xx() {
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class), (Object[]) any()))
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
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", cb, readCircuitBreaker);

        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class), (Object[]) any()))
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
    void getStatus_circuitBreakerOpen_doesNotBlockCancel() {
        // WR-04: getStatus(조회) 전용 CB를 OPEN시켜도 cancel(취소 실행)은 별도 CB라 차단되지 않음
        CircuitBreaker fastOpenRead = CircuitBreaker.of("fast-open-read",
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(100)
                .build());
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", circuitBreaker, fastOpenRead);

        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));
        assertThatThrownBy(() -> sut.getStatus("key1")).isInstanceOf(PgServiceException.class);
        assertThatThrownBy(() -> sut.getStatus("key1")).isInstanceOf(PgServiceException.class);
        assertThat(fastOpenRead.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        PgCancelResult result = PgCancelResult.approved("tx-123");
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(result));
        assertThat(sut.cancel("key1", new BigDecimal("5000"), "환불")).isEqualTo(result);
    }

    // getForEntity(String url, Class<T> responseType, Object... uriVariables)
    @Test
    void getStatus_returns_approved_result() {
        PgCancelResult result = PgCancelResult.approved("tx-123");
        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(result));

        PgCancelResult actual = sut.getStatus("key1");

        assertThat(actual).isEqualTo(result);
        assertThat(actual.isApproved()).isTrue();
    }

    @Test
    void getStatus_returns_retryable_failed_result() {
        PgCancelResult result = PgCancelResult.retryableFailed("tx-123");
        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(result));

        PgCancelResult actual = sut.getStatus("key1");

        assertThat(actual.isFailed()).isTrue();
        assertThat(actual.isRetryable()).isTrue();
    }

    @Test
    void getStatus_returns_pending_result() {
        PgCancelResult result = PgCancelResult.pending("tx-123");
        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(result));

        assertThat(sut.getStatus("key1").isPending()).isTrue();
    }

    @Test
    void getStatus_throws_pg_service_exception_on_non_2xx() {
        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenReturn(ResponseEntity.status(500).build());

        assertThatThrownBy(() -> sut.getStatus("key1"))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void cancel_error_propagates_unwrapped_not_wrapped_as_pg_service_exception() {
        // WR-05: Error(OutOfMemoryError 등)까지 PgServiceException으로 위장하지 않고 그대로 전파
        when(restTemplate.postForEntity(anyString(), any(), eq(PgCancelResult.class), (Object[]) any()))
            .thenThrow(new StackOverflowError("모의 Error"));

        assertThatThrownBy(() -> sut.cancel("key1", new BigDecimal("5000"), "환불"))
            .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void getStatus_throws_pg_service_exception_on_network_failure() {
        when(restTemplate.getForEntity(anyString(), eq(PgCancelResult.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));

        assertThatThrownBy(() -> sut.getStatus("key1"))
            .isInstanceOf(PgServiceException.class);
    }
}
