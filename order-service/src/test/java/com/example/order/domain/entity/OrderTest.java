package com.example.order.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void new_order_waits_for_payment() {
        assertThat(Order.create(100L).getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void payment_completed_moves_pending_order_once() {
        Order order = Order.of(1L, 100L, OrderStatus.PENDING);

        assertThat(order.markPaymentCompleted()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
        assertThat(order.markPaymentCompleted()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_WAITING);
    }

    @Test
    void late_completed_event_does_not_revive_cancelled_order() {
        Order order = Order.of(1L, 100L, OrderStatus.CANCELLED);

        assertThat(order.markPaymentCompleted()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void should_become_cancelled_when_cancel_called() {
        Order order = Order.of(1L, 100L, OrderStatus.PAID);
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void should_become_partial_cancelled_when_partialCancel_called() {
        Order order = Order.of(1L, 100L, OrderStatus.PAID);
        order.partialCancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIAL_CANCELLED);
    }
}
