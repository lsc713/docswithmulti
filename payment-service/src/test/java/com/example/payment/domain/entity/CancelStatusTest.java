package com.example.payment.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CancelStatus enum")
class CancelStatusTest {

    @Test
    @DisplayName("isFinal — COMPLETED, FAILED만 true")
    void isFinal() {
        assertTrue(CancelStatus.COMPLETED.isFinal());
        assertTrue(CancelStatus.FAILED.isFinal());
        assertFalse(CancelStatus.PENDING.isFinal());
        assertFalse(CancelStatus.PROCESSING.isFinal());
    }

    @Test
    @DisplayName("isProcessing — PROCESSING만 true")
    void isProcessing() {
        assertTrue(CancelStatus.PROCESSING.isProcessing());
        assertFalse(CancelStatus.PENDING.isProcessing());
        assertFalse(CancelStatus.COMPLETED.isProcessing());
        assertFalse(CancelStatus.FAILED.isProcessing());
    }
}
