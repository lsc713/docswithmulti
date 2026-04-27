package com.example.order.application.usecase;

import com.example.order.application.service.CreateOrderCommand;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;

import java.util.List;

public interface CreateOrderUseCase {
    record Result(Order order, List<OrderItem> items) {}
    Result create(CreateOrderCommand command);
}
