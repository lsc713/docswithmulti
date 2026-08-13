package com.example.payment.application.service;

import com.example.payment.application.interfaces.OrderVerifyPort;
import com.example.payment.application.interfaces.ProductStockPort;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.infrastructure.exception.OrderVerifyRejectedException;
import com.example.payment.infrastructure.exception.OrderVerifyUnavailableException;
import com.example.payment.infrastructure.exception.StockInsufficientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePaymentService (비-TX 오케스트레이터)")
class CreatePaymentServiceTest {

    @Mock OrderVerifyPort orderVerifyPort;
    @Mock ProductStockPort productStockPort;
    @Mock PaymentCreateTxWriter paymentCreateTxWriter;
    @Mock com.example.payment.application.interfaces.StockReleaseRetryRepository stockReleaseRetryRepository;

    private static final long VERIFIED_ORDER_ID = 999L;

    private CreatePaymentService service;

    @BeforeEach
    void setUp() {
        service = new CreatePaymentService(orderVerifyPort, productStockPort, paymentCreateTxWriter,
            stockReleaseRetryRepository);
        lenient().when(orderVerifyPort.verify(anyLong(), any())).thenReturn(VERIFIED_ORDER_ID);
        lenient().when(productStockPort.reserve(anyString(), any())).thenAnswer(invocation ->
            ((List<ProductStockPort.Item>) invocation.getArgument(1)).stream()
                .map(item -> new ProductStockPort.ReservedItem(
                    item.skuId(), item.productId(), unitPrice(item.skuId()), item.qty()))
                .toList());
    }

    @Test
    @DisplayName("정상 — reserve(TX 밖) 성공 후 persist 결과 반환, reserve 인자(paymentKey/skuId/qty) 검증")
    void shouldReserveThenPersist() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            1L, 100L, "TOSS", 90,
            List.of(
                new CreatePaymentCommand.Item(10L, 200L, "상품A", BigDecimal.valueOf(30_000), 500L, 2),
                new CreatePaymentCommand.Item(11L, 201L, "상품B", BigDecimal.valueOf(70_000), 501L, 1)
            )
        );

        Payment savedPayment = Payment.reconstruct(
            1L, "pay_abc123", 1L, 100L, "TOSS",
            BigDecimal.valueOf(100_000), "KRW", 90,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now()
        );
        List<PaymentItem> savedItems = List.of(
            PaymentItem.reconstruct(1L, 1L, 10L, 200L, 200L, "상품A",
                BigDecimal.valueOf(30_000), 500L, 2, PaymentItemStatus.ACTIVE),
            PaymentItem.reconstruct(2L, 1L, 11L, 201L, 201L, "상품B",
                BigDecimal.valueOf(70_000), 501L, 1, PaymentItemStatus.ACTIVE)
        );
        when(paymentCreateTxWriter.persist(any(), anyString(), any(), anyLong()))
            .thenReturn(new Result(savedPayment, savedItems));

        Result result = service.create(command);

        assertNotNull(result.payment());
        assertEquals(2, result.items().size());

        // reserve가 persist 이전에, 합산 금액·skuId/qty 매핑으로 호출됐는지 검증
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductStockPort.Item>> itemsCaptor =
            ArgumentCaptor.forClass(List.class);
        verify(productStockPort).reserve(keyCaptor.capture(), itemsCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("pay_"));
        assertEquals(
            List.of(
                new ProductStockPort.Item(200L, 500L, 2),
                new ProductStockPort.Item(201L, 501L, 1)),
            itemsCaptor.getValue()
        );

        // persist는 reserve에 전달된 것과 동일한 paymentKey + 합산 금액 + order 검증된 orderId로 호출
        verify(paymentCreateTxWriter).persist(eq(command),
            eq(keyCaptor.getValue()), eq(BigDecimal.valueOf(100_000)), eq(VERIFIED_ORDER_ID));

        // order 검증(PLINK-03)이 reserve보다 먼저 호출됐는지 순서 검증
        InOrder inOrder = inOrder(orderVerifyPort, productStockPort);
        inOrder.verify(orderVerifyPort).verify(eq(100L), eq(List.of(10L, 11L)));
        inOrder.verify(productStockPort).reserve(anyString(), any());
    }

    @Test
    @DisplayName("fail-closed — reserve가 StockInsufficientException 던지면 persist 미호출·예외 전파")
    void shouldNotPersistWhenReserveFails() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            2L, 200L, "KG", 30,
            List.of(new CreatePaymentCommand.Item(20L, 300L, "상품C", BigDecimal.valueOf(50_000), 502L, 3))
        );
        doThrow(new StockInsufficientException("재고 부족"))
            .when(productStockPort).reserve(anyString(), any());

        assertThrows(StockInsufficientException.class, () -> service.create(command));

        verify(productStockPort).reserve(anyString(), any());
        verifyNoInteractions(paymentCreateTxWriter);
    }

    @Test
    @DisplayName("fail-closed(PLINK-01/03) — order 검증 장애(OrderVerifyUnavailableException) 시 reserve·persist 미호출·예외 전파")
    void shouldNotReserveOrPersistWhenOrderVerifyUnavailable() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            2L, 200L, "KG", 30,
            List.of(new CreatePaymentCommand.Item(20L, 300L, "상품C", BigDecimal.valueOf(50_000), 502L, 3))
        );
        doThrow(new OrderVerifyUnavailableException("order-service 장애"))
            .when(orderVerifyPort).verify(anyLong(), any());

        assertThrows(OrderVerifyUnavailableException.class, () -> service.create(command));

        verifyNoInteractions(productStockPort);
        verifyNoInteractions(paymentCreateTxWriter);
    }

    @Test
    @DisplayName("fail-closed(PLINK-01/03) — order 검증 거부(OrderVerifyRejectedException) 시 reserve·persist 미호출·예외 전파")
    void shouldNotReserveOrPersistWhenOrderVerifyRejected() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            2L, 200L, "KG", 30,
            List.of(new CreatePaymentCommand.Item(20L, 300L, "상품C", BigDecimal.valueOf(50_000), 502L, 3))
        );
        doThrow(new OrderVerifyRejectedException(
            com.example.payment.common.exception.ErrorCode.ORDER_OWNERSHIP_MISMATCH, "소유 불일치"))
            .when(orderVerifyPort).verify(anyLong(), any());

        assertThrows(OrderVerifyRejectedException.class, () -> service.create(command));

        verifyNoInteractions(productStockPort);
        verifyNoInteractions(paymentCreateTxWriter);
    }

    @Test
    @DisplayName("단일 아이템 — 금액 합산 후 persist 호출")
    void shouldCreatePaymentWithSingleItem() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            2L, 200L, "KG", 30,
            List.of(new CreatePaymentCommand.Item(20L, 300L, "상품C", BigDecimal.valueOf(50_000), 502L, 1))
        );
        Payment savedPayment = Payment.reconstruct(
            2L, "pay_xyz", 2L, 200L, "KG",
            BigDecimal.valueOf(50_000), "KRW", 30,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now()
        );
        when(paymentCreateTxWriter.persist(any(), anyString(), any(), anyLong()))
            .thenReturn(new Result(savedPayment, List.of()));

        Result result = service.create(command);

        assertNotNull(result);
        verify(paymentCreateTxWriter).persist(eq(command), anyString(),
            eq(BigDecimal.valueOf(50_000)), eq(VERIFIED_ORDER_ID));
    }

    private static BigDecimal unitPrice(long skuId) {
        return switch ((int) skuId) {
            case 500 -> BigDecimal.valueOf(15_000);
            case 501 -> BigDecimal.valueOf(70_000);
            default -> BigDecimal.valueOf(50_000);
        };
    }
}
