package com.example.order.common.exception.application;

import java.util.List;

public class OrderItemNotFoundException extends RuntimeException implements NonRetryableException {

    public OrderItemNotFoundException(List<Long> orderItemIds) {
        super("OrderItem not found: orderItemIds=" + orderItemIds);
    }
}
