package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.CreatePaymentUseCase.Result;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import com.example.payment.domain.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePaymentService")
class CreatePaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentItemRepository paymentItemRepository;

    private CreatePaymentService service;

    @BeforeEach
    void setUp() {
        service = new CreatePaymentService(paymentRepository, paymentItemRepository);
    }

    @Test
    @DisplayName("정상 결제 생성 — 아이템 합산 금액으로 Payment 저장 후 Result 반환")
    void shouldCreatePaymentWithTotalAmount() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            1L, 100L, "TOSS", 90,
            List.of(
                new CreatePaymentCommand.Item(10L, 200L, "상품A", BigDecimal.valueOf(30_000)),
                new CreatePaymentCommand.Item(11L, 201L, "상품B", BigDecimal.valueOf(70_000))
            )
        );

        Payment savedPayment = Payment.reconstruct(
            1L, "pay_abc123", 1L, 100L, "TOSS",
            BigDecimal.valueOf(100_000), "KRW", 90,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now()
        );
        when(paymentRepository.save(any())).thenReturn(savedPayment);

        List<PaymentItem> savedItems = List.of(
            PaymentItem.reconstruct(1L, 1L, 10L, 200L, 200L, "상품A",
                BigDecimal.valueOf(30_000), PaymentItemStatus.ACTIVE),
            PaymentItem.reconstruct(2L, 1L, 11L, 201L, 201L, "상품B",
                BigDecimal.valueOf(70_000), PaymentItemStatus.ACTIVE)
        );
        when(paymentItemRepository.saveAll(any())).thenReturn(savedItems);

        Result result = service.create(command);

        assertNotNull(result.payment());
        assertEquals(2, result.items().size());
        verify(paymentRepository).save(argThat(p ->
            p.getTotalAmount().compareTo(BigDecimal.valueOf(100_000)) == 0
        ));
        verify(paymentItemRepository).saveAll(argThat(items -> items.size() == 2));
    }

    @Test
    @DisplayName("단일 아이템 결제 생성")
    void shouldCreatePaymentWithSingleItem() {
        CreatePaymentCommand command = new CreatePaymentCommand(
            2L, 200L, "KG", 30,
            List.of(new CreatePaymentCommand.Item(20L, 300L, "상품C", BigDecimal.valueOf(50_000)))
        );

        Payment savedPayment = Payment.reconstruct(
            2L, "pay_xyz", 2L, 200L, "KG",
            BigDecimal.valueOf(50_000), "KRW", 30,
            PaymentStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now()
        );
        when(paymentRepository.save(any())).thenReturn(savedPayment);
        when(paymentItemRepository.saveAll(any())).thenReturn(List.of());

        Result result = service.create(command);

        assertNotNull(result);
        verify(paymentRepository).save(argThat(p ->
            p.getTotalAmount().compareTo(BigDecimal.valueOf(50_000)) == 0
                && p.getMerchantId() == 2L
        ));
    }
}
