package com.example.payment.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.fixture.PaymentFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    Logger logger;
    ListAppender<ILoggingEvent> logAppender;

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
            Instant.now().minus(10, ChronoUnit.MINUTES),
            null, null);
        // CR-03: compensateAndFail은 compensate 호출 전 이 원자 UPDATE로 승자를 가린다.
        // 대부분의 테스트는 "내가 승자"인 시나리오이므로 기본값 1(성공)로 lenient 스텁.
        lenient().when(cancelRequestRepository.compareAndSetFailed(anyLong())).thenReturn(1);

        // WR-02: 레이스 패자 로그 레벨(WARN/ERROR) 검증용 appender
        logger = (Logger) LoggerFactory.getLogger(ProcessingRecoveryService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("PG 조회 실패 시 PROCESSING 유지 (skip)")
    void pg_get_status_exception_keeps_processing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenThrow(new RuntimeException("PG 연결 실패"));

        service.recoverAll();

        verify(cancelRequestRepository, never()).save(any());
        verify(cancelTxWriter, never()).saveTx3(any(), any(), any());
    }

    @Test
    @DisplayName("WR-01: PG 상태가 APPROVED/FAILED/PENDING 어디에도 안 걸리면 경고 로그만 남기고 상태 변경 없음")
    void pg_unknown_status_logs_warning_and_does_nothing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any()))
            .thenReturn(new PgCancelResult("pg_tx_001", "UNKNOWN_STATUS", false));

        service.recoverAll();

        verify(cancelRequestRepository, never()).save(any());
        verify(cancelRequestRepository, never()).compareAndSetFailed(anyLong());
        verify(cancelTxWriter, never()).saveTx3(any(), any(), any());
        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("PG APPROVED → TX3 재실행 + COMPLETED 이력")
    void pg_approved_runs_tx3() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.approved("pg_tx_001"));
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
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.failed("pg_tx_001"));

        service.recoverAll();

        verify(cancelRequestRepository).compareAndSetFailed(eq(10L));
        verify(riskManagementPort).compensate(eq(10L), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=0 → 원자 UPDATE + 재조회(count=1) → PG 재호출 성공 시 TX3")
    void pg_failed_retryable_retries_pg_and_succeeds() {
        CancelRequest refreshed = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 1,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES),
            null, null);
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));
        when(cancelRequestRepository.findById(10L))
            .thenReturn(Optional.of(refreshed));
        when(pgCancelPort.cancel(anyString(), any(), anyString())).thenReturn(PgCancelResult.approved("pg_tx_002"));
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(processing);

        service.recoverAll();

        verify(cancelRequestRepository).incrementPgRetryCount(10L);
        verify(pgCancelPort).cancel(eq("pay_test_001"), eq(BigDecimal.valueOf(50000)), anyString());
        verify(cancelTxWriter).saveTx3(eq(refreshed), eq(payment), eq(List.of(10L, 11L)));
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=5(최대) → 재호출 없이 보상 + FAILED")
    void pg_failed_retryable_max_retries_compensates() {
        CancelRequest maxRetry = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 5,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES),
            null, null);
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(maxRetry));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));

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
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.pending("pg_tx_001"));

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
            Instant.now().minus(2, ChronoUnit.HOURS),
            null, null);
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(timedOut));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.pending("pg_tx_001"));

        service.recoverAll();

        verify(riskManagementPort).compensate(anyLong(), eq(1L), eq(BigDecimal.valueOf(50000)));
        verify(operationAlertPort).alertPgPendingTimeout(eq(10L), eq("pay_test_001"), any(Instant.class));
        verify(historyRepository).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("PG FAILED retryable=true, pgRetryCount=4 → 원자 UPDATE 후 재조회 count=5(==MAX) → PG 재호출 없이 즉시 보상 + FAILED")
    void pg_failed_retryable_refetched_count_at_max_compensates_without_pg_call() {
        CancelRequest almostMax = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 4,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES),
            null, null);
        CancelRequest refreshed = CancelRequest.reconstruct(
            10L, 1L, "hash_abc", BigDecimal.valueOf(50000), "고객 변심",
            List.of(10L, 11L), CancelStatus.PROCESSING, 5,
            null, null,
            Instant.now().minus(10, ChronoUnit.MINUTES),
            Instant.now().minus(10, ChronoUnit.MINUTES),
            null, null);
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(almostMax));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.retryableFailed("pg_tx_001"));
        when(cancelRequestRepository.findById(10L))
            .thenReturn(Optional.of(refreshed));

        service.recoverAll();

        // 원자 UPDATE 호출 확인 + 재조회한 값(5)이 MAX_PG_RETRIES 도달 → PG 재호출 없이 즉시 보상 + FAILED
        verify(cancelRequestRepository).incrementPgRetryCount(10L);
        verify(pgCancelPort, never()).cancel(anyString(), any(), anyString());
        verify(cancelRequestRepository).compareAndSetFailed(eq(10L));
        verify(riskManagementPort).compensate(anyLong(), anyLong(), any());
    }

    // ──────────────────────────────────────────────────────────
    // WR-02: 정상 레이스 패자(InvalidPaymentItemStatusException) 로그 레벨
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("WR-02: saveTx3 동시 재실행 레이스 패자 → ERROR 아닌 WARN(동시 처리 경쟁)으로 로깅")
    void raceLoss_logs_warn_not_error() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.approved("pg_tx_001"));
        when(cancelTxWriter.saveTx3(any(), any(), any()))
            .thenThrow(new InvalidPaymentItemStatusException(10L, PaymentItemStatus.CANCELLED));

        service.recoverAll();

        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(logAppender.list).anyMatch(
            e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("동시 처리 경쟁"));
    }

    @Test
    @DisplayName("WR-02 회귀 방지: 레이스가 아닌 일반 BusinessException은 여전히 ERROR로 로깅")
    void otherBusinessException_stillLogsError() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.approved("pg_tx_001"));
        when(cancelTxWriter.saveTx3(any(), any(), any()))
            .thenThrow(new com.example.payment.domain.exception.InvalidCancelStateTransitionException(
                CancelStatus.PROCESSING, CancelStatus.COMPLETED));

        service.recoverAll();

        assertThat(logAppender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("대상 없으면 아무 작업 없음")
    void no_stale_processing_does_nothing() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of());

        service.recoverAll();

        verifyNoInteractions(pgCancelPort, cancelTxWriter, riskManagementPort, operationAlertPort);
    }

    // ──────────────────────────────────────────────────────────
    // CR-03: 이중 보상 방지 — compareAndSetFailed가 0(다른 스레드 선점)이면 compensate skip
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("compareAndSetFailed=0(다른 스레드가 이미 FAILED 전이 완료) → compensate 호출 없이 skip")
    void compensateAndFail_skips_when_another_thread_already_transitioned() {
        when(cancelRequestRepository.findProcessingUpdatedBefore(any())).thenReturn(List.of(processing));
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(pgCancelPort.getStatus(anyString(), any())).thenReturn(PgCancelResult.failed("pg_tx_001"));
        // 이 스레드는 레이스에서 짐 — 원자 UPDATE가 0건 갱신(이미 다른 스레드가 FAILED로 전이함)
        when(cancelRequestRepository.compareAndSetFailed(10L)).thenReturn(0);

        service.recoverAll();

        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
        verify(compensationRetryRepository, never()).save(anyLong(), anyLong(), any());
        verify(historyRepository, never()).record(anyLong(), eq(CancelStatus.FAILED), anyString());
    }
}
