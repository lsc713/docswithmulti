package com.example.user.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentMethod 도메인 엔티티")
class PaymentMethodTest {
    @Test
    @DisplayName("카드 결제수단 생성")
    void shouldCreateCard() {
        PaymentMethod pm = PaymentMethod.ofCard(1L, "1234", "신한카드", true);
        assertEquals(PaymentMethodType.CARD, pm.getType());
        assertEquals("1234", pm.getCardNumber());
        assertEquals("신한카드", pm.getCardCompany());
        assertNull(pm.getBankName());
        assertTrue(pm.isDefault());
    }

    @Test
    @DisplayName("계좌이체 결제수단 생성")
    void shouldCreateBankTransfer() {
        PaymentMethod pm = PaymentMethod.ofBankTransfer(1L, "국민은행", "7890", false);
        assertEquals(PaymentMethodType.BANK_TRANSFER, pm.getType());
        assertEquals("국민은행", pm.getBankName());
        assertEquals("7890", pm.getAccountNumber());
        assertNull(pm.getCardNumber());
    }

    @Test
    @DisplayName("기본 결제수단 해제")
    void shouldClearDefault() {
        PaymentMethod pm = PaymentMethod.ofCard(1L, "1234", "신한카드", true);
        pm.clearDefault();
        assertFalse(pm.isDefault());
    }
}
