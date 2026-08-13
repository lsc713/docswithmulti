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
        return new Order(0, userId, OrderStatus.PENDING);
    }

    public static Order of(long id, long userId, OrderStatus status) {
        return new Order(id, userId, status);
    }

    public boolean markPaymentCompleted() {
        if (status != OrderStatus.PENDING && status != OrderStatus.PAYMENT_VERIFYING) {
            return false;
        }
        status = OrderStatus.DELIVERY_WAITING;
        return true;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public void partialCancel() {
        this.status = OrderStatus.PARTIAL_CANCELLED;
    }
}
