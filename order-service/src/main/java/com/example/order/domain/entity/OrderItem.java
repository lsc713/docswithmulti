package com.example.order.domain.entity;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class OrderItem {

    private final long id;
    private final long orderId;
    private final long productId;
    private final String itemName;
    private final BigDecimal price;
    private OrderItemStatus status;

    private OrderItem(long id, long orderId, long productId, String itemName,
                      BigDecimal price, OrderItemStatus status) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.itemName = itemName;
        this.price = price;
        this.status = status;
    }

    public static OrderItem create(long orderId, long productId, String itemName, BigDecimal price) {
        return new OrderItem(0, orderId, productId, itemName, price, OrderItemStatus.ACTIVE);
    }

    public static OrderItem of(long id, long orderId, long productId, String itemName,
                               BigDecimal price, OrderItemStatus status) {
        return new OrderItem(id, orderId, productId, itemName, price, status);
    }

    public void cancel() {
        this.status = OrderItemStatus.CANCELLED;
    }

    public boolean isCancelled() {
        return this.status == OrderItemStatus.CANCELLED;
    }
}
