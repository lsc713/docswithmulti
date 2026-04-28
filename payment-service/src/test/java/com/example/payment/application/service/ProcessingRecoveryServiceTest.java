package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessingRecoveryService")
class ProcessingRecoveryServiceTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PgCancelPort pgCancelPort;
    @Mock CancelTxWriter cancelTxWriter;
    @Mock OperationAlertPort operationAlertPort;

    ProcessingRecoveryService service;
    Payment payment;           // merchantId=1L, paymentKey="pay_test_001"
    CancelRequest processing;  // id=10L, paymentId=1L, cancelAmount=50000, cancelItemIds=[10,11], pgRetryCount=0

    @BeforeEach
    void setUp() {
        service = new ProcessingRecoveryService(
            cancelRequestRepository, historyRepository,
            riskManagementPort, compensationRetryRepository,
            paymentRepository, pgCancelPort, cancelTxWriter, operationAlertPort
        );
        payment = PaymentFixture.completedPayment();
        processing = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 0,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES)
        );
    }

    @Test
    @DisplayName("PG 조회 실패 시 PROCESSING 유지 (skip)")
    void pg_get_status_exception_keeps_processing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenThrow(new RuntimeException("PG 연결 실패"));

        service.recoverAll();

        verify(cancelRequestRepository, never()).save(any());
        verify(cancelTxWriter, never()).saveTx3(any(), any(), any());
    }

    @Test
    @DisplayName("PG APPROVED → TX3 재실행 + COMPLETED 이력")
    void pg_approved_runs_tx3() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.approved("pg_tx_001"));
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(processing);

        service.recoverAll();

        verify(cancelTxWriter).saveTx3(eq(processing), eq(payment), eq(List.of(10L, 11L)));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.COMPLETED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=false → 보상 + FAILED + 이력")
    void pg_failed_non_retryable_compensates_and_fails() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.failed("pg_tx_001"));

        service.recoverAll();

        verify(riskManagementPort).compensate(eq(10L), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(cancelRequestRepository).save(any());
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=0 → PG 재호출 성공 시 TX3")
    void pg_failed_retryable_retries_pg_and_succeeds() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));
        when(pgCancelPort.cancel(anyString(), any(), anyString())).thenReturn(PgCancelResult.approved("pg_tx_002"));
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(processing);

        service.recoverAll();

        verify(pgCancelPort).cancel(eq("pay_test_001"), eq(BigDecimal.valueOf(50000)), anyString());
        verify(cancelTxWriter).saveTx3(any(), eq(payment), eq(List.of(10L, 11L)));
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=5(최대) → 재호출 없이 보상 + FAILED")
    void pg_failed_retryable_max_retries_compensates() {
        CancelRequest maxRetry = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 5,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES)
        );
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(maxRetry));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));

        service.recoverAll();

        verify(pgCancelPort, never()).cancel(anyString(), any(), anyString());
        verify(riskManagementPort).compensate(anyLong(), anyLong(), any());
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG PENDING 최초 → markPgPending 저장, 보상/알림 없음")
    void pg_pending_first_time_marks_pg_pending() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.pending("pg_tx_001"));

        service.recoverAll();

        verify(cancelRequestRepository, times(1)).save(any());
        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
        verifyNoInteractions(operationAlertPort);
    }

    @Test
    @DisplayName("PG PENDING 1시간 초과 → 보상 + FAILED + 운영팀 알림")
    void pg_pending_timeout_compensates_and_alerts() {
        CancelRequest timedOut = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 0,
            null,
            Instant.now().minus(2, ChronoUnit.HOURS),  // pgPendingSince 2시간 전
            Instant.now().minus(2, ChronoUnit.HOURS),
            Instant.now().minus(2, ChronoUnit.HOURS)
        );
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(timedOut));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString())).thenReturn(PgCancelResult.pending("pg_tx_001"));

        service.recoverAll();

        verify(riskManagementPort).compensate(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(operationAlertPort).alertPgPendingTimeout(eq(10L), eq("pay_test_001"), any(Instant.class));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("대상 없으면 아무 작업 없음")
    void no_stale_processing_does_nothing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of());

        service.recoverAll();

        verifyNoInteractions(pgCancelPort, cancelTxWriter, riskManagementPort, operationAlertPort);
    }
}
