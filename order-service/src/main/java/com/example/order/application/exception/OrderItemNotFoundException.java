package com.example.order.application.exception;

import java.util.List;

public class OrderItemNotFoundException extends RuntimeException implements NonRetryableException {

    public OrderItemNotFoundException(List<Long> orderItemIds) {
        super("OrderItem not found: orderItemIds=" + orderItemIds);
    }
}
