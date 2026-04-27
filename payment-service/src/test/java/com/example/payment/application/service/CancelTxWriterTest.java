package com.example.payment.application.service;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelTxWriter")
class CancelTxWriterTest {

    @Mock CancelRequestRepository cancelRequestRepository;
    @Mock PaymentItemRepository paymentItemRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock CancelEventOutboxRepository outboxRepository;

    private CancelTxWriter writer;

    private Payment payment;
    private PaymentItem itemA;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        CancelDomainService domainService = new CancelDomainService(new CancelPeriodPolicy(clock));

        writer = new CancelTxWriter(
            cancelRequestRepository, paymentItemRepository, paymentRepository, outboxRepository, domainService
        );

        payment = PaymentFixture.completedPayment();
        itemA = PaymentItem.reconstruct(1L, payment.getId(), 10L, 100L, 200L, "상품A",
            BigDecimal.valueOf(30000), PaymentItemStatus.ACTIVE);
    }

    @Test
    @DisplayName("saveTx1: PENDING 상태로 CancelRequest를 저장한다")
    void saveTx1_savesCancelRequestAsPending() {
        CancelRequest req = CancelRequest.create(
            payment.getId(), "hash-001", BigDecimal.valueOf(30000), "고객 변심");

        when(cancelRequestRepository.save(any())).thenAnswer(inv -> {
            CancelRequest cr = inv.getArgument(0);
            return CancelRequest.reconstruct(1L, cr.getPaymentId(), cr.getRequestHash(),
                cr.getCancelAmount(), cr.getCancelReason(), cr.getStatus(),
                null, null, null, null, cr.getCreatedAt(), cr.getUpdatedAt());
        });

        CancelRequest result = writer.saveTx1(req);

        assertEquals(CancelStatus.PENDING, result.getStatus());
        verify(cancelRequestRepository).save(req);
    }

    @Test
    @DisplayName("saveTx2: PROCESSING 상태로 전환 후 저장한다")
    void saveTx2_transitionsToCancelRequestToProcessing() {
        CancelRequest req = CancelRequest.create(
            payment.getId(), "hash-001", BigDecimal.valueOf(30000), "고객 변심");
        req = CancelRequest.reconstruct(1L, req.getPaymentId(), req.getRequestHash(),
            req.getCancelAmount(), req.getCancelReason(), req.getStatus(),
            null, null, null, null, req.getCreatedAt(), req.getUpdatedAt());

        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelRequest result = writer.saveTx2(req);

        assertEquals(CancelStatus.PROCESSING, result.getStatus());
        verify(cancelRequestRepository).save(req);
    }

    @Test
    @DisplayName("saveTx3: PaymentItem을 FOR UPDATE로 재조회하고 COMPLETED 상태로 저장한다")
    void saveTx3_reloadsItemsForUpdateAndSavesCompleted() {
        CancelRequest req = CancelRequest.create(
            payment.getId(), "hash-001", BigDecimal.valueOf(30000), "고객 변심");
        req = CancelRequest.reconstruct(1L, req.getPaymentId(), req.getRequestHash(),
            req.getCancelAmount(), req.getCancelReason(), CancelStatus.PROCESSING,
            null, null, null, null, req.getCreatedAt(), req.getUpdatedAt());

        when(paymentItemRepository.findAllByPaymentIdForUpdate(payment.getId()))
            .thenReturn(List.of(itemA));
        doNothing().when(paymentItemRepository).saveAll(anyList());
        when(cancelRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CancelRequest result = writer.saveTx3(req, payment, List.of(1L));

        assertEquals(CancelStatus.COMPLETED, result.getStatus());
        verify(paymentItemRepository).findAllByPaymentIdForUpdate(payment.getId());
        verify(paymentItemRepository).saveAll(anyList());
        verify(outboxRepository).insertIfAbsent(any(), any(), anyList());
    }
}
