package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.exception.CancelPeriodExceededException;
import com.example.payment.domain.exception.InvalidPaymentItemStatusException;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.PaymentFixture;
import com.example.payment.fixture.PaymentItemFixture;
import com.example.payment.infrastructure.exception.RiskServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPaymentService")
class CancelPaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock CancelRequestHistoryRepository historyRepository;
    @Mock CancelEventOutboxRepository outboxRepository;
    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock RiskManagementPort riskManagementPort;
    @Mock PgCancelPort pgCancelPort;
    @Mock CancelTxWriter cancelTxWriter;

    private CancelPaymentService service;

    private Payment payment;
    private PaymentItem itemA;
    private PaymentItem itemB;
    private CancelPaymentCommand command;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));

        service = new CancelPaymentService(
            paymentRepository, paymentItemRepository, cancelRequestRepository,
            historyRepository, outboxRepository, compensationRetryRepository,
            riskManagementPort, pgCancelPort, domainService, cancelTxWriter
        );

        payment = PaymentFixture.completedPayment(); // paymentKey="pay_test_001", merchantId=1
        itemA = PaymentItem.reconstruct(1L, payment.getId(), 10L, 100L, 200L, "상품A",
            BigDecimal.valueOf(30000), PaymentItemStatus.ACTIVE);
        itemB = PaymentItem.reconstruct(2L, payment.getId(), 11L, 100L, 200L, "상품B",
            BigDecimal.valueOf(70000), PaymentItemStatus.ACTIVE);

        command = new CancelPaymentCommand("pay_test_001", "고객 변심", List.of(1L));
    }

    @Test
    @DisplayName("should_throw_payment_not_found_when_payment_missing")
    void shouldThrowPaymentNotFoundWhenPaymentMissing() {
        when(paymentRepository.findByPaymentKey("pay_test_001")).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> service.cancel(command));
    }

    @Test
    @DisplayName("should_return_existing_result_when_cancel_request_completed")
    void shouldReturnExistingResultWhenCancelRequestCompleted() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));

        CancelRequest existing = CancelRequest.create(
            payment.getId(), "any-hash", BigDecimal.valueOf(30000), "변심");
        existing.toProcessing();
        existing.toCompleted();

        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.of(existing));

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(riskManagementPort, never()).validateAndReserve(anyLong(), anyLong(), any(), any());
    }

    // ──────────────────────────────────────────────────────────
    // 정상 취소 (COMPLETED)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 취소 — risk·PG 모두 성공 시 COMPLETED 반환")
    void shouldCompleteCancelSuccessfully() {
        when(paymentRepository.findByPaymentKey("pay_test_001")).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));
        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.empty());

        CancelRequest pendingWithId = pendingCancelRequest(1L, payment.getId());
        when(cancelTxWriter.saveTx1(any())).thenReturn(pendingWithId);

        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L, BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(30_000), BigDecimal.valueOf(9_970_000)));

        CancelRequest processingWithId = reconstruct(1L, payment.getId(), CancelStatus.PROCESSING);
        when(cancelTxWriter.saveTx2(any())).thenReturn(processingWithId);

        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(new PgCancelResult("pg-tx-001", "APPROVED"));

        CancelRequest completed = reconstruct(1L, payment.getId(), CancelStatus.COMPLETED);
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(completed);

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(riskManagementPort).validateAndReserve(
            eq(payment.getMerchantId()), anyLong(), eq(BigDecimal.valueOf(30_000)), any());
        verify(pgCancelPort).cancel(eq("pay_test_001"), eq(BigDecimal.valueOf(30_000)), anyString());
        verify(cancelTxWriter).saveTx1(any());
        verify(cancelTxWriter).saveTx2(any());
        verify(cancelTxWriter).saveTx3(any(), eq(payment), eq(List.of(1L)));
    }

    // ──────────────────────────────────────────────────────────
    // 멱등성 — PENDING / PROCESSING 기존 건 재시도 시 즉시 반환
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("멱등성 — PENDING 기존 건 재시도 시 risk 호출 없이 즉시 반환")
    void shouldReturnExistingResultWhenCancelRequestPending() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));

        CancelRequest pending = pendingCancelRequest(1L, payment.getId());
        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.of(pending));

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.PENDING, result.getStatus());
        verify(riskManagementPort, never()).validateAndReserve(anyLong(), anyLong(), any(), any());
    }

    // ──────────────────────────────────────────────────────────
    // FAILED 재시도
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_raise_failed_to_pending_and_continue_when_existing_failed")
    void shouldRaiseFailedToPendingAndContinueWhenExistingFailed() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));
        CancelRequest failed = CancelRequest.create(
            payment.getId(), "any-hash", BigDecimal.valueOf(30000), "변심");
        failed.toProcessing();
        failed.toFailed("이전 오류");

        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.of(failed));
        // FAILED 재시도 시 raiseToPending 후 cancelRequestRepository.save() 직접 호출
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelRequest withId = CancelRequest.reconstruct(100L, failed.getPaymentId(),
            failed.getRequestHash(), failed.getCancelAmount(), failed.getCancelReason(),
            CancelStatus.PENDING, null, null, null, null,
            failed.getCreatedAt(), failed.getUpdatedAt());
        when(cancelTxWriter.saveTx1(any())).thenReturn(withId);
        when(cancelTxWriter.saveTx2(any())).thenAnswer(inv -> {
            CancelRequest cr = inv.getArgument(0);
            cr.toProcessing();
            return cr;
        });
        CancelRequest completed = CancelRequest.reconstruct(100L, withId.getPaymentId(),
            withId.getRequestHash(), withId.getCancelAmount(), withId.getCancelReason(),
            CancelStatus.COMPLETED, null, null, null, null,
            withId.getCreatedAt(), withId.getUpdatedAt());
        when(cancelTxWriter.saveTx3(any(), any(), any())).thenReturn(completed);

        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L, BigDecimal.valueOf(5000000),
                BigDecimal.valueOf(30000), BigDecimal.valueOf(4970000)));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(new PgCancelResult("pg-tx-001", "APPROVED"));

        CancelRequest result = service.cancel(command);

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
    }

    // ──────────────────────────────────────────────────────────
    // Risk 실패 → FAILED + compensation_retry 저장
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("risk 실패 → cancel_request FAILED + compensation_retry 저장")
    void shouldMarkFailedAndSaveCompensationRetryWhenRiskFails() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));
        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.empty());

        CancelRequest pendingWithId = pendingCancelRequest(1L, payment.getId());
        when(cancelTxWriter.saveTx1(any())).thenReturn(pendingWithId);

        // Risk 호출 실패
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenThrow(new RiskServiceException("risk 서비스 다운"));
        // 보상 호출도 실패 → compensation_retry 저장
        doThrow(new RiskServiceException("보상 실패"))
            .when(riskManagementPort).compensate(anyLong(), anyLong(), any());

        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(RiskServiceException.class, () -> service.cancel(command));

        // compensation_retry.save 호출 확인
        verify(compensationRetryRepository).save(
            eq(pendingWithId.getId()), eq(payment.getMerchantId()), any(BigDecimal.class));
        // cancel_request FAILED 상태로 저장 확인
        verify(cancelRequestRepository).save(
            argThat(cr -> cr.getStatus() == CancelStatus.FAILED));
        // TX2, TX3 호출 없음
        verify(cancelTxWriter, never()).saveTx2(any());
        verify(cancelTxWriter, never()).saveTx3(any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────
    // 이미 취소된 아이템 포함 → TX1 이전에 예외
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 취소된 아이템 포함 → InvalidPaymentItemStatusException, TX1 미실행")
    void shouldThrowWhenTargetItemAlreadyCancelled() {
        PaymentItem cancelledItemA = PaymentItemFixture.cancelled(payment.getId(), 10L, BigDecimal.valueOf(30_000));
        // id=1 인 cancelled 아이템을 reconstruct로 생성
        PaymentItem cancelledWithId = PaymentItem.reconstruct(
            1L, payment.getId(), 10L, 100L, 200L, "상품A",
            BigDecimal.valueOf(30_000), PaymentItemStatus.CANCELLED);

        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(cancelledWithId, itemB));
        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.empty());

        assertThrows(InvalidPaymentItemStatusException.class, () -> service.cancel(command));

        verify(cancelTxWriter, never()).saveTx1(any());
        verify(riskManagementPort, never()).validateAndReserve(anyLong(), anyLong(), any(), any());
    }

    // ──────────────────────────────────────────────────────────
    // 취소 기간 초과 → TX3에서 CancelPeriodExceededException 전파
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("취소 기간 초과 — TX3에서 CancelPeriodExceededException 전파")
    void shouldPropagateCancelPeriodExceededFromTx3() {
        when(paymentRepository.findByPaymentKey(any())).thenReturn(Optional.of(payment));
        when(paymentItemRepository.findAllByPaymentIdOrderByIdAsc(anyLong()))
            .thenReturn(List.of(itemA, itemB));
        when(cancelRequestRepository.findByPaymentIdAndRequestHash(anyLong(), anyString()))
            .thenReturn(Optional.empty());

        CancelRequest pendingWithId = pendingCancelRequest(1L, payment.getId());
        when(cancelTxWriter.saveTx1(any())).thenReturn(pendingWithId);
        when(riskManagementPort.validateAndReserve(anyLong(), anyLong(), any(), any()))
            .thenReturn(new RiskReserveResult(1L, BigDecimal.valueOf(10_000_000),
                BigDecimal.valueOf(30_000), BigDecimal.valueOf(9_970_000)));
        when(cancelTxWriter.saveTx2(any())).thenReturn(reconstruct(1L, payment.getId(), CancelStatus.PROCESSING));
        when(pgCancelPort.cancel(any(), any(), any()))
            .thenReturn(new PgCancelResult("pg-tx-001", "APPROVED"));

        // TX3에서 기간 초과 예외 발생
        when(cancelTxWriter.saveTx3(any(), any(), any()))
            .thenThrow(new CancelPeriodExceededException(
                payment.getCreatedAt(), payment.getCancelPeriodDays()));

        assertThrows(CancelPeriodExceededException.class, () -> service.cancel(command));
    }

    // ──────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ──────────────────────────────────────────────────────────

    private CancelRequest pendingCancelRequest(long id, long paymentId) {
        return CancelRequest.reconstruct(id, paymentId, "hash",
            BigDecimal.valueOf(30_000), "변심",
            CancelStatus.PENDING, null, null, null, null,
            Instant.now(), Instant.now());
    }

    private CancelRequest reconstruct(long id, long paymentId, CancelStatus status) {
        return CancelRequest.reconstruct(id, paymentId, "hash",
            BigDecimal.valueOf(30_000), "변심",
            status, Instant.now(), Instant.now(), null, null,
            Instant.now(), Instant.now());
    }
}
