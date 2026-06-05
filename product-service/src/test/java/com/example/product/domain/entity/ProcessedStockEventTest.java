package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedStockEventTest {

    @Test
    @DisplayName("of()로 ProcessedStockEvent 생성")
    void ofCreatesEvent() {
        ProcessedStockEvent event = ProcessedStockEvent.of(42L);

        assertThat(event.getCancelRequestId()).isEqualTo(42L);
        assertThat(event.getCreatedAt()).isNotNull();
    }
}
