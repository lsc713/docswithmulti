package com.example.payment.application.service;

import com.example.payment.application.exception.PaymentAttemptException;
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
    private PaymentAttemptService service;

    @BeforeEach
    void setUp() {
        orderVerifyPort = mock(OrderVerifyPort.class);
        productStockPort = mock(ProductStockPort.class);
        paymentRepository = mock(PaymentRepository.class);
        txWriter = mock(PaymentAttemptTxWriter.class);
        tossPaymentPort = mock(TossPaymentPort.class);
        service = new PaymentAttemptService(
            orderVerifyPort, productStockPort, paymentRepository, txWriter, tossPaymentPort,
            mock(StockReleaseRetryRepository.class));
        ReflectionTestUtils.setField(service, "clientKey", "test_ck");
        ReflectionTestUtils.setField(service, "customerKeySalt", "salt");
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
