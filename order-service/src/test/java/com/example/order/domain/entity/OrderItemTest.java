package com.example.order.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemTest {

    @Test
    void should_become_cancelled_when_cancel_called() {
        OrderItem item = OrderItem.of(1L, 10L, 100L, "상품A", java.math.BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        item.cancel();
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(item.isCancelled()).isTrue();
    }

    @Test
    void should_not_be_cancelled_when_active() {
        OrderItem item = OrderItem.of(1L, 10L, 100L, "상품A", java.math.BigDecimal.valueOf(10000), OrderItemStatus.ACTIVE);
        assertThat(item.isCancelled()).isFalse();
    }
}
