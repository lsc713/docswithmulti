package com.example.order.application.service;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.application.interfaces.OrderRepository;
import com.example.order.application.interfaces.ProcessedCancelEventRepository;
import com.example.order.application.model.CancelRestoreLegStatus;
import com.example.order.application.usecase.InspectCancelRestoreUseCase;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InspectCancelRestoreService implements InspectCancelRestoreUseCase {

    private final ProcessedCancelEventRepository processedCancelEventRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

    public InspectCancelRestoreService(
        ProcessedCancelEventRepository processedCancelEventRepository,
        OrderItemRepository orderItemRepository,
        OrderRepository orderRepository
    ) {
        this.processedCancelEventRepository = processedCancelEventRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public Result inspect(Command command) {
        boolean processed = processedCancelEventRepository
            .existsByCancelRequestId(command.cancelRequestId());
        List<OrderItem> foundItems = orderItemRepository.findAllByIdIn(command.orderItemIds());
        Map<Long, OrderItem> foundById = new LinkedHashMap<>();
        foundItems.forEach(item -> foundById.put(item.getId(), item));

        List<Evidence> evidence = new ArrayList<>();
        for (Long requestedId : command.orderItemIds()) {
            if (!foundById.containsKey(requestedId)) {
                evidence.add(new Evidence(requestedId, "MISSING"));
            }
        }
        if (!evidence.isEmpty()) {
            return inconsistent(evidence);
        }

        if (!processed) {
            foundItems.stream()
                .filter(OrderItem::isCancelled)
                .map(item -> new Evidence(item.getId(), item.getStatus().name()))
                .forEach(evidence::add);
            return evidence.isEmpty()
                ? new Result(CancelRestoreLegStatus.NOT_APPLIED, List.of())
                : inconsistent(evidence);
        }

        foundItems.stream()
            .filter(item -> !item.isCancelled())
            .map(item -> new Evidence(item.getId(), item.getStatus().name()))
            .forEach(evidence::add);
        if (!evidence.isEmpty()) {
            return inconsistent(evidence);
        }

        List<Long> orderIds = foundItems.stream()
            .map(OrderItem::getOrderId)
            .distinct()
            .toList();
        if (orderIds.size() != 1) {
            orderIds.forEach(orderId -> evidence.add(new Evidence(orderId, "MULTIPLE_ORDERS")));
            return inconsistent(evidence);
        }

        long orderId = orderIds.getFirst();
        return orderRepository.findById(orderId)
            .map(order -> {
                OrderStatus status = order.getStatus();
                if (status == OrderStatus.CANCELLED || status == OrderStatus.PARTIAL_CANCELLED) {
                    return new Result(CancelRestoreLegStatus.APPLIED, List.of());
                }
                return inconsistent(List.of(new Evidence(orderId, status.name())));
            })
            .orElseGet(() -> inconsistent(List.of(new Evidence(orderId, "MISSING"))));
    }

    private Result inconsistent(List<Evidence> evidence) {
        return new Result(CancelRestoreLegStatus.INCONSISTENT, evidence);
    }
}
