package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.infrastructure.exception.PgServiceException;
import com.example.payment.infrastructure.http.dto.TossCancel;
import com.example.payment.infrastructure.http.dto.TossPaymentResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * D-01 정정: 실 Toss Payments 계약 매핑 검증.
 * GET /v1/payments/{paymentKey} → TossPaymentResponse{status,balanceAmount,cancels[]}
 * POST /v1/payments/{paymentKey}/cancel → 동일 형태의 갱신된 Payment 응답
 */
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
        readCircuitBreaker = CircuitBreaker.ofDefaults("test-read");
        sut = new PgCancelHttpClient(restTemplate, "http://pg-test", circuitBreaker, readCircuitBreaker);
    }

    private TossCancel cancel(BigDecimal amount, String transactionKey, String cancelStatus) {
        return new TossCancel(amount, "환불", BigDecimal.ZERO, BigDecimal.ZERO, transactionKey, "2026-07-29T00:00:00", cancelStatus);
    }

    // ──────────────────────────────────────────────────────────
    // cancel() — POST /v1/payments/{paymentKey}/cancel 응답 매핑
    // ──────────────────────────────────────────────────────────

    @Test
    void cancel_maps_toss_response_to_approved_with_transactionKey() {
        TossPaymentResponse response = new TossPaymentResponse(
            "CANCELED", BigDecimal.ZERO, List.of(cancel(new BigDecimal("5000"), "tx-123", "DONE")));
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult result = sut.cancel("key1", new BigDecimal("5000"), "환불");

        assertThat(result.isApproved()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("tx-123");
    }

    @Test
    void cancel_partial_canceled_status_maps_to_approved() {
        TossPaymentResponse response = new TossPaymentResponse(
            "PARTIAL_CANCELED", new BigDecimal("10000"),
            List.of(cancel(new BigDecimal("5000"), "tx-partial", "DONE")));
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult result = sut.cancel("key1", new BigDecimal("5000"), "환불");

        assertThat(result.isApproved()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("tx-partial");
    }

    @Test
    void cancel_waiting_for_deposit_maps_to_pending() {
        TossPaymentResponse response = new TossPaymentResponse(
            "WAITING_FOR_DEPOSIT", BigDecimal.ZERO,
            List.of(cancel(new BigDecimal("5000"), "tx-pending", "IN_PROGRESS")));
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult result = sut.cancel("key1", new BigDecimal("5000"), "환불");

        assertThat(result.isPending()).isTrue();
        assertThat(result.pgTransactionId()).isEqualTo("tx-pending");
    }

    @Test
    void cancel_unexpected_status_throws_pg_service_exception() {
        TossPaymentResponse response = new TossPaymentResponse("ABORTED", BigDecimal.ZERO, List.of());
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        assertThatThrownBy(() -> sut.cancel("key1", new BigDecimal("5000"), "환불"))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void cancel_throws_pg_service_exception_on_non_2xx() {
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
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

        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));

        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);
        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);

        // CB is now OPEN → next call should also throw PgServiceException
        assertThatThrownBy(() -> sut.cancel("key1", BigDecimal.ONE, "test"))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void cancel_error_propagates_unwrapped_not_wrapped_as_pg_service_exception() {
        // WR-05: Error(OutOfMemoryError 등)까지 PgServiceException으로 위장하지 않고 그대로 전파
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenThrow(new StackOverflowError("모의 Error"));

        assertThatThrownBy(() -> sut.cancel("key1", new BigDecimal("5000"), "환불"))
            .isInstanceOf(StackOverflowError.class);
    }

    // ──────────────────────────────────────────────────────────
    // getStatus(paymentKey, cancelAmount) — GET /v1/payments/{paymentKey} 매핑 규칙 1~7
    // ──────────────────────────────────────────────────────────

    @Test
    void getStatus_rule1_matching_done_cancel_by_amount_returns_approved() {
        TossPaymentResponse response = new TossPaymentResponse(
            "PARTIAL_CANCELED", new BigDecimal("5000"),
            List.of(cancel(new BigDecimal("5000"), "tx-match", "DONE")));
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isApproved()).isTrue();
        assertThat(actual.pgTransactionId()).isEqualTo("tx-match");
    }

    @Test
    void getStatus_rule2_full_canceled_without_amount_match_returns_approved_with_last_cancel() {
        TossPaymentResponse response = new TossPaymentResponse(
            "CANCELED", BigDecimal.ZERO,
            List.of(
                cancel(new BigDecimal("3000"), "tx-first", "DONE"),
                cancel(new BigDecimal("2000"), "tx-last", "DONE")));
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        // 우리가 조회하는 금액(5000)과 정확히 일치하는 단일 cancel이 없는 케이스
        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isApproved()).isTrue();
        assertThat(actual.pgTransactionId()).isEqualTo("tx-last");
    }

    @Test
    void getStatus_rule2_full_canceled_with_no_cancels_returns_approved_with_null_txKey() {
        TossPaymentResponse response = new TossPaymentResponse("CANCELED", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isApproved()).isTrue();
        assertThat(actual.pgTransactionId()).isNull();
    }

    @Test
    void getStatus_rule3_active_done_payment_returns_retryable_failed() {
        TossPaymentResponse response = new TossPaymentResponse("DONE", new BigDecimal("5000"), List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isFailed()).isTrue();
        assertThat(actual.isRetryable()).isTrue();
    }

    @Test
    void getStatus_rule4_in_progress_returns_pending() {
        TossPaymentResponse response = new TossPaymentResponse("IN_PROGRESS", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        assertThat(sut.getStatus("key1", new BigDecimal("5000")).isPending()).isTrue();
    }

    @Test
    void getStatus_rule4_waiting_for_deposit_returns_pending() {
        TossPaymentResponse response = new TossPaymentResponse("WAITING_FOR_DEPOSIT", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        assertThat(sut.getStatus("key1", new BigDecimal("5000")).isPending()).isTrue();
    }

    @Test
    void getStatus_rule5_aborted_returns_non_retryable_failed() {
        TossPaymentResponse response = new TossPaymentResponse("ABORTED", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isFailed()).isTrue();
        assertThat(actual.isRetryable()).isFalse();
    }

    @Test
    void getStatus_rule5_expired_returns_non_retryable_failed() {
        TossPaymentResponse response = new TossPaymentResponse("EXPIRED", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isFailed()).isTrue();
        assertThat(actual.isRetryable()).isFalse();
    }

    @Test
    void getStatus_rule6_partial_canceled_without_matching_done_cancel_returns_retryable_failed() {
        TossPaymentResponse response = new TossPaymentResponse(
            "PARTIAL_CANCELED", new BigDecimal("3000"),
            List.of(cancel(new BigDecimal("2000"), "tx-other", "DONE")));
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        PgCancelResult actual = sut.getStatus("key1", new BigDecimal("5000"));

        assertThat(actual.isFailed()).isTrue();
        assertThat(actual.isRetryable()).isTrue();
    }

    @Test
    void getStatus_rule7_unknown_status_throws_pg_service_exception() {
        TossPaymentResponse response = new TossPaymentResponse("READY", BigDecimal.ZERO, List.of());
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));

        assertThatThrownBy(() -> sut.getStatus("key1", new BigDecimal("5000")))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void getStatus_throws_pg_service_exception_on_non_2xx() {
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.status(500).build());

        assertThatThrownBy(() -> sut.getStatus("key1", new BigDecimal("5000")))
            .isInstanceOf(PgServiceException.class);
    }

    @Test
    void getStatus_throws_pg_service_exception_on_network_failure() {
        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));

        assertThatThrownBy(() -> sut.getStatus("key1", new BigDecimal("5000")))
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

        when(restTemplate.getForEntity(anyString(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenThrow(new RuntimeException("connection error"));
        assertThatThrownBy(() -> sut.getStatus("key1", new BigDecimal("5000"))).isInstanceOf(PgServiceException.class);
        assertThatThrownBy(() -> sut.getStatus("key1", new BigDecimal("5000"))).isInstanceOf(PgServiceException.class);
        assertThat(fastOpenRead.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        TossPaymentResponse response = new TossPaymentResponse(
            "CANCELED", BigDecimal.ZERO, List.of(cancel(new BigDecimal("5000"), "tx-123", "DONE")));
        when(restTemplate.postForEntity(anyString(), any(), eq(TossPaymentResponse.class), (Object[]) any()))
            .thenReturn(ResponseEntity.ok(response));
        assertThat(sut.cancel("key1", new BigDecimal("5000"), "환불").isApproved()).isTrue();
    }
}
