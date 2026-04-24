package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class OrderItem {

    private final long id;
    private final long orderId;
    private OrderItemStatus status;

    private OrderItem(long id, long orderId, OrderItemStatus status) {
        this.id = id;
        this.orderId = orderId;
        this.status = status;
    }

    public static OrderItem of(long id, long orderId, OrderItemStatus status) {
        return new OrderItem(id, orderId, status);
    }

    public void cancel() {
        this.status = OrderItemStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return this.status == OrderItemStatus.CANCELLED;
    }
}
