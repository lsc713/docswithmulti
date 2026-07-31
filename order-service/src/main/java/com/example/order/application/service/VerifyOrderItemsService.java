package com.example.order.application.service;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.usecase.VerifyOrderItemsUseCase;
import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.service.OrderItemVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VerifyOrderItemsService implements VerifyOrderItemsUseCase {

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    // 순수 POJO 도메인 판정 — Spring 의존 없이 직접 생성(D-CONTEXT-3, 별도 @Bean 불필요).
    private final OrderItemVerifier orderItemVerifier = new OrderItemVerifier();

    @Override
    @Transactional(readOnly = true)
    public Result verify(List<Long> orderItemIds, long requesterUserId) {
        List<OrderItem> foundItems = orderItemRepository.findAllByIdIn(orderItemIds);
        long orderId = orderItemVerifier.resolveOrderId(orderItemIds, foundItems);
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalStateException("Order not found for id=" + orderId));
        orderItemVerifier.checkOwnership(order, requesterUserId);
        return new Result(orderId);
    }
}
