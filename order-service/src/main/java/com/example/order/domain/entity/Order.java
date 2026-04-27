package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class Order {

    private final long id;
    private final long userId;
    private OrderStatus status;

    private Order(long id, long userId, OrderStatus status) {
        this.id = id;
        this.userId = userId;
        this.status = status;
    }

    public static Order create(long userId) {
        return new Order(0, userId, OrderStatus.DELIVERY_WAITING);
    }

    public static Order of(long id, long userId, OrderStatus status) {
        return new Order(id, userId, status);
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void partialCancel() {
        this.status = OrderStatus.PARTIAL_CANCELLED;
    }
}
