package com.example.order.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void should_become_cancelled_when_cancel_called() {
        Order order = Order.of(1L, OrderStatus.PAID);
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void should_become_partial_cancelled_when_partialCancel_called() {
        Order order = Order.of(1L, OrderStatus.PAID);
        order.partialCancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_CANCELLED);
    }
}
