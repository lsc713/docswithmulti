package com.example.order.application.service;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.usecase.CreateOrderUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    @Override
    @Transactional
    public Result create(CreateOrderCommand command) {
        Order order = orderRepository.insert(Order.create(command.userId()));

        List<OrderItem> items = command.items().stream()
            .map(item -> OrderItem.create(order.getId(), item.productId(), item.itemName(), item.price()))
            .toList();
        List<OrderItem> savedItems = orderItemRepository.insertAll(items);

        return new Result(order, savedItems);
    }
}
