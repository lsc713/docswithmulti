package com.example.payment.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentItemStatus enum")
class PaymentItemStatusTest {

    @Test
    @DisplayName("isCancellable — ACTIVE만 true")
    void isCancellable() {
        assertTrue(PaymentItemStatus.ACTIVE.isCancellable());
        assertFalse(PaymentItemStatus.CANCELLED.isCancellable());
    }

    @Test
    @DisplayName("isFinal — CANCELLED만 true")
    void isFinal() {
        assertTrue(PaymentItemStatus.CANCELLED.isFinal());
        assertFalse(PaymentItemStatus.ACTIVE.isFinal());
    }

    @Test
    @DisplayName("getDescription — 각 상태별 한글 설명 반환")
    void getDescription() {
        assertEquals("활성", PaymentItemStatus.ACTIVE.getDescription());
        assertEquals("전액 취소", PaymentItemStatus.CANCELLED.getDescription());
    }
}
