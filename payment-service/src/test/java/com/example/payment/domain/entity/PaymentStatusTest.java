package com.example.payment.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentStatus enum")
class PaymentStatusTest {

    @Test
    @DisplayName("isCancellable — COMPLETED, PARTIAL_CANCELLED만 true")
    void isCancellable() {
        assertTrue(PaymentStatus.COMPLETED.isCancellable());
        assertTrue(PaymentStatus.PARTIAL_CANCELLED.isCancellable());
        assertFalse(PaymentStatus.PENDING.isCancellable());
        assertFalse(PaymentStatus.CANCELLED.isCancellable());
        assertFalse(PaymentStatus.CANCEL_FAILED.isCancellable());
    }

    @Test
    @DisplayName("isFinal — CANCELLED, CANCEL_FAILED만 true")
    void isFinal() {
        assertTrue(PaymentStatus.CANCELLED.isFinal());
        assertTrue(PaymentStatus.CANCEL_FAILED.isFinal());
        assertFalse(PaymentStatus.PENDING.isFinal());
        assertFalse(PaymentStatus.COMPLETED.isFinal());
        assertFalse(PaymentStatus.PARTIAL_CANCELLED.isFinal());
    }

    @Test
    @DisplayName("getDescription — 각 상태별 한글 설명 반환")
    void getDescription() {
        assertEquals("진행 중", PaymentStatus.PENDING.getDescription());
        assertEquals("결제 완료", PaymentStatus.COMPLETED.getDescription());
        assertEquals("부분 취소", PaymentStatus.PARTIAL_CANCELLED.getDescription());
        assertEquals("전액 취소", PaymentStatus.CANCELLED.getDescription());
        assertEquals("취소 실패", PaymentStatus.CANCEL_FAILED.getDescription());
    }
}
