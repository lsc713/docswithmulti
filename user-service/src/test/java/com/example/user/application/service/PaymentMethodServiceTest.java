package com.example.user.application.service;

import com.example.user.application.exception.PaymentMethodNotFoundException;
import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.application.usecase.PaymentMethodUseCase.*;
import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodService")
class PaymentMethodServiceTest {
    @Mock PaymentMethodRepository paymentMethodRepository;
    private PaymentMethodService paymentMethodService;

    @BeforeEach
    void setUp() { paymentMethodService = new PaymentMethodService(paymentMethodRepository); }

    private PaymentMethod sampleCard() {
        return PaymentMethod.reconstruct(1L, 1L, PaymentMethodType.CARD,
                "1234-5678-9012-3456", "신한", null, null, true, Instant.now(), Instant.now());
    }

    @Test @DisplayName("getPaymentMethods — 목록 조회")
    void shouldGetPaymentMethods() {
        when(paymentMethodRepository.findAllByUserId(1L)).thenReturn(List.of(sampleCard()));
        assertEquals(1, paymentMethodService.getPaymentMethods(1L).size());
    }

    @Test @DisplayName("create — 카드 결제수단 생성")
    void shouldCreateCard() {
        when(paymentMethodRepository.save(any())).thenReturn(sampleCard());
        PaymentMethod result = paymentMethodService.create(1L,
                new CreateCommand(PaymentMethodType.CARD, "1234-5678-9012-3456", "신한", null, null, true));
        assertEquals(PaymentMethodType.CARD, result.getType());
    }

    @Test @DisplayName("create — 계좌이체 결제수단 생성")
    void shouldCreateBankTransfer() {
        PaymentMethod bankMethod = PaymentMethod.reconstruct(2L, 1L, PaymentMethodType.BANK_TRANSFER,
                null, null, "국민은행", "123-456-789", false, Instant.now(), Instant.now());
        when(paymentMethodRepository.save(any())).thenReturn(bankMethod);
        PaymentMethod result = paymentMethodService.create(1L,
                new CreateCommand(PaymentMethodType.BANK_TRANSFER, null, null, "국민은행", "123-456-789", false));
        assertEquals(PaymentMethodType.BANK_TRANSFER, result.getType());
    }

    @Test @DisplayName("delete — 결제수단 삭제")
    void shouldDeletePaymentMethod() {
        when(paymentMethodRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(sampleCard()));
        paymentMethodService.delete(1L, 1L);
        verify(paymentMethodRepository).deleteById(1L);
    }

    @Test @DisplayName("delete — 없는 결제수단 → PaymentMethodNotFoundException")
    void shouldThrowOnDeleteNotFound() {
        when(paymentMethodRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
        assertThrows(PaymentMethodNotFoundException.class, () -> paymentMethodService.delete(1L, 99L));
    }
}
