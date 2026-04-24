package com.example.payment.application.service;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.dto.RiskReserveResult;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.*;
import com.example.payment.domain.policy.CancelPeriodPolicy;
import com.example.payment.domain.service.CancelDomainService;
import com.example.payment.fixture.PaymentFixture;
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
}
