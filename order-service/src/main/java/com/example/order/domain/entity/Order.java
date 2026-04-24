package com.example.order.domain.entity;

import lombok.Getter;

@Getter
public class Order {

    private final long id;
    private OrderStatus status;

    private Order(long id, OrderStatus status) {
        this.id = id;
        this.status = status;
    }

    public static Order of(long id, OrderStatus status) {
        return new Order(id, status);
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void partialCancel() {
        this.status = OrderStatus.PARTIAL_CANCELLED;
    }
}
