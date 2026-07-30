package com.example.payment.application.service;

import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePaymentService — 예약 성공 후 persist 실패 보상(RSV-03)")
class CreatePaymentCompensationTest {

    @Mock ProductStockPort productStockPort;
    @Mock PaymentCreateTxWriter paymentCreateTxWriter;
    @Mock StockReleaseRetryRepository stockReleaseRetryRepository;

    private CreatePaymentService service;

    @BeforeEach
    void setUp() {
        service = new CreatePaymentService(
            productStockPort, paymentCreateTxWriter, stockReleaseRetryRepository, new ObjectMapper());
    }

    private CreatePaymentCommand command() {
        return new CreatePaymentCommand(
            1L, 100L, "TOSS", 90,
            List.of(
                new CreatePaymentCommand.Item(10L, 200L, "상품A", BigDecimal.valueOf(30_000), 500L, 2),
                new CreatePaymentCommand.Item(11L, 201L, "상품B", BigDecimal.valueOf(70_000), 501L, 1)
            )
        );
    }

    @Test
    @DisplayName("persist 성공 → release/enqueue 없음")
    void persistSuccess_noCompensation() {
        Payment saved = Payment.reconstruct(
            1L, "pay_ok", 1L, 100L, "TOSS",
            BigDecimal.valueOf(100_000), "KRW", 90,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now());
        when(paymentCreateTxWriter.persist(any(), anyString(), any()))
            .thenReturn(new Result(saved, List.of()));

        service.create(command());

        verify(productStockPort, never()).release(anyString(), any());
        verify(stockReleaseRetryRepository, never()).enqueue(anyString(), anyString());
    }

    @Test
    @DisplayName("persist 실패 → release 1회 best-effort 호출 + 원예외 전파(payment 미생성)")
    void persistFails_releaseCompensates() {
        RuntimeException boom = new RuntimeException("persist 폭발");
        when(paymentCreateTxWriter.persist(any(), anyString(), any())).thenThrow(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.create(command()));
        assertSame(boom, thrown);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductStockPort.Item>> itemsCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(productStockPort).release(keyCaptor.capture(), itemsCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("pay_"));
        assertEquals(
            List.of(new ProductStockPort.Item(500L, 2), new ProductStockPort.Item(501L, 1)),
            itemsCaptor.getValue());
        verify(stockReleaseRetryRepository, never()).enqueue(anyString(), anyString());
    }

    @Test
    @DisplayName("persist 실패 + release도 실패 → stock_release_retry 적재 + 원예외 전파")
    void persistFails_releaseFails_enqueued() {
        RuntimeException boom = new RuntimeException("persist 폭발");
        when(paymentCreateTxWriter.persist(any(), anyString(), any())).thenThrow(boom);
        doThrow(new RuntimeException("release 폭발"))
            .when(productStockPort).release(anyString(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.create(command()));
        assertSame(boom, thrown);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(stockReleaseRetryRepository).enqueue(keyCaptor.capture(), jsonCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("pay_"));
        // items 직렬화 보존 검증 (payment_item이 롤백돼 없으므로 여기 보존)
        assertTrue(jsonCaptor.getValue().contains("500"));
        assertTrue(jsonCaptor.getValue().contains("501"));
    }
}
