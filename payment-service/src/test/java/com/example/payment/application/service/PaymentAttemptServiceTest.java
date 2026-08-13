package com.example.payment.application.service;

import com.example.payment.application.exception.PaymentAttemptException;
import com.example.payment.application.exception.PaymentApprovalRejectedException;
import com.example.payment.application.interfaces.*;
import com.example.payment.domain.entity.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentAttemptServiceTest {
    private OrderVerifyPort orderVerifyPort;
    private ProductStockPort productStockPort;
    private PaymentRepository paymentRepository;
    private PaymentAttemptTxWriter txWriter;
    private TossPaymentPort tossPaymentPort;
    private StockReleaseRetryRepository stockReleaseRetryRepository;
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        orderVerifyPort = mock(OrderVerifyPort.class);
        productStockPort = mock(ProductStockPort.class);
        paymentRepository = mock(PaymentRepository.class);
        txWriter = mock(PaymentAttemptTxWriter.class);
        tossPaymentPort = mock(TossPaymentPort.class);
        stockReleaseRetryRepository = mock(StockReleaseRetryRepository.class);
        service = new PaymentAttemptService(
            orderVerifyPort, productStockPort, paymentRepository, txWriter, tossPaymentPort,
            stockReleaseRetryRepository);
        ReflectionTestUtils.setField(service, "clientKey", "test_ck");
        ReflectionTestUtils.setField(service, "customerKeySalt", "salt");
        ReflectionTestUtils.setField(service, "recoveryBatchSize", 10);
    }

    @Test
    void prepare_uses_server_price_and_returns_non_identifying_customer_key() {
        CreatePaymentCommand command = command(BigDecimal.ONE);
        when(orderVerifyPort.verify(42L, List.of(10L))).thenReturn(7L);
        when(productStockPort.reserve(anyString(), any())).thenReturn(List.of(
            new ProductStockPort.ReservedItem(
                500L, 200L, BigDecimal.valueOf(10_000), 2)));

        var result = service.prepare(command);

        assertThat(result.amount()).isEqualByComparingTo("20000");
        assertThat(result.customerKey()).startsWith("customer_").doesNotContain("42");
        assertThat(result.clientKey()).isEqualTo("test_ck");
        verify(txWriter).prepare(eq(result.paymentRequestId()), argThat(priced ->
            priced.items().get(0).itemAmount().compareTo(BigDecimal.valueOf(20_000)) == 0),
            eq(BigDecimal.valueOf(20_000)), eq(7L));
    }

    @Test
    void confirm_mismatch_does_not_call_toss() {
        Payment pending = pending("request-1");
        when(paymentRepository.findByPaymentRequestId("request-1"))
            .thenReturn(java.util.Optional.of(pending));

        assertThatThrownBy(() -> service.confirm(
            "request-1", 42L, "toss_key", "different", BigDecimal.valueOf(20_000)))
            .isInstanceOf(PaymentAttemptException.class);

        verifyNoInteractions(tossPaymentPort, txWriter);
    }

    @Test
    void confirm_calls_toss_once_and_completed_retry_reuses_result() {
        Payment pending = pending("request-1");
        when(paymentRepository.findByPaymentRequestId("request-1"))
            .thenReturn(java.util.Optional.of(pending));
        pending.attachPaymentKey("toss_key");
        when(txWriter.attach("request-1", 42L, "toss_key"))
            .thenReturn(new PaymentAttemptTxWriter.AttachResult(pending, true));
        Payment completed = pending("request-1");
        completed.attachPaymentKey("toss_key");
        completed.complete();
        when(txWriter.complete("request-1")).thenReturn(completed);

        assertThat(service.confirm(
            "request-1", 42L, "toss_key", "request-1", BigDecimal.valueOf(20_000)).status())
            .isEqualTo(com.example.payment.domain.entity.PaymentStatus.COMPLETED);

        when(paymentRepository.findByPaymentRequestId("request-1"))
            .thenReturn(java.util.Optional.of(completed));
        service.confirm(
            "request-1", 42L, "toss_key", "request-1", BigDecimal.valueOf(20_000));

        verify(tossPaymentPort, times(1)).confirm(
            "toss_key", "request-1", BigDecimal.valueOf(20_000));
    }

    @Test
    void fail_releases_stock_only_when_unattached_pending_attempt_changes_to_failed() {
        Payment failed = pending("request-1");
        failed.failUnconfirmed();
        var items = List.of(new ProductStockPort.Item(200L, 500L, 2));
        when(txWriter.failUnconfirmed("request-1", 42L))
            .thenReturn(new PaymentAttemptTxWriter.FailureResult(failed, true, items));

        assertThat(service.fail("request-1", 42L).status())
            .isEqualTo(com.example.payment.domain.entity.PaymentStatus.FAILED);

        verify(productStockPort).release("request-1", items);
    }

    @Test
    void fail_keeps_attached_attempt_pending_for_recovery() {
        Payment attached = pending("request-1");
        attached.attachPaymentKey("toss_key");
        when(txWriter.failUnconfirmed("request-1", 42L))
            .thenReturn(new PaymentAttemptTxWriter.FailureResult(attached, false, List.of()));

        assertThat(service.fail("request-1", 42L).status())
            .isEqualTo(com.example.payment.domain.entity.PaymentStatus.PENDING);

        verifyNoInteractions(productStockPort);
    }

    @Test
    void recover_done_completes_but_aborted_fails_and_releases() {
        Payment done = pending("done");
        done.attachPaymentKey("done_key");
        Payment failed = pending("failed");
        failed.attachPaymentKey("failed_key");
        failed.failConfirmed();
        var items = List.of(new ProductStockPort.Item(200L, 500L, 2));
        when(paymentRepository.findPendingRecoveryCandidates(any(), any(), eq(10)))
            .thenReturn(List.of(done, failed));
        when(tossPaymentPort.getStatus("done_key")).thenReturn(TossPaymentPort.Status.DONE);
        when(tossPaymentPort.getStatus("failed_key")).thenReturn(TossPaymentPort.Status.ABORTED);
        when(txWriter.failConfirmed("failed"))
            .thenReturn(new PaymentAttemptTxWriter.FailureResult(failed, true, items));

        service.recoverPending();

        verify(txWriter).complete("done");
        verify(productStockPort).release("failed", items);
    }

    @Test
    void explicit_toss_rejection_fails_and_releases_but_timeout_stays_pending() {
        Payment attached = pending("request-1");
        attached.attachPaymentKey("toss_key");
        when(paymentRepository.findByPaymentRequestId("request-1"))
            .thenReturn(java.util.Optional.of(attached));
        when(txWriter.attach("request-1", 42L, "toss_key"))
            .thenReturn(new PaymentAttemptTxWriter.AttachResult(attached, true));
        var items = List.of(new ProductStockPort.Item(200L, 500L, 2));
        when(txWriter.failConfirmed("request-1"))
            .thenReturn(new PaymentAttemptTxWriter.FailureResult(attached, true, items));
        doThrow(new PaymentApprovalRejectedException()).when(tossPaymentPort)
            .confirm("toss_key", "request-1", BigDecimal.valueOf(20_000));

        assertThatThrownBy(() -> service.confirm(
            "request-1", 42L, "toss_key", "request-1", BigDecimal.valueOf(20_000)))
            .isInstanceOf(PaymentAttemptException.class);
        verify(productStockPort).release("request-1", items);

        reset(tossPaymentPort, productStockPort, txWriter);
        when(txWriter.attach("request-1", 42L, "toss_key"))
            .thenReturn(new PaymentAttemptTxWriter.AttachResult(attached, true));
        doThrow(new PaymentAttemptException(com.example.payment.common.exception.ErrorCode.PG_SERVICE_UNAVAILABLE))
            .when(tossPaymentPort).confirm(anyString(), anyString(), any());

        assertThatThrownBy(() -> service.confirm(
            "request-1", 42L, "toss_key", "request-1", BigDecimal.valueOf(20_000)))
            .isInstanceOf(PaymentAttemptException.class);
        verify(txWriter, never()).failConfirmed(anyString());
        verifyNoInteractions(productStockPort);
    }

    private CreatePaymentCommand command(BigDecimal clientAmount) {
        return new CreatePaymentCommand(1L, 42L, "NORMAL", 90, List.of(
            new CreatePaymentCommand.Item(
                10L, 200L, "상품", clientAmount, 500L, 2)));
    }

    private Payment pending(String requestId) {
        return Payment.pendingAttempt(
            requestId, 1L, 42L, "NORMAL", BigDecimal.valueOf(20_000), "KRW", 90, 7L);
    }
}
