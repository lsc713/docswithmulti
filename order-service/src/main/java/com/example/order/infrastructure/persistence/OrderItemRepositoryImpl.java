package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.OrderItemRepository;
import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class OrderItemRepositoryImpl implements OrderItemRepository {

    private final OrderItemJpaRepository jpa;

    @Override
    public List<OrderItem> insertAll(List<OrderItem> items) {
        List<OrderItemJpaEntity> entities = items.stream()
            .map(OrderItemJpaEntity::from)
            .toList();
        return jpa.saveAll(entities).stream()
            .map(OrderItemJpaEntity::toDomain)
            .toList();
    }

    @Override
    public List<OrderItem> findAllByIdIn(List<Long> ids) {
        return jpa.findAllById(ids).stream()
            .map(OrderItemJpaEntity::toDomain)
            .toList();
    }

    @Override
    public List<OrderItem> findAllByOrderId(long orderId) {
        return jpa.findAllByOrderId(orderId).stream()
            .map(OrderItemJpaEntity::toDomain)
            .toList();
    }

    @Override
    public void saveAll(List<OrderItem> items) {
        Map<Long, OrderItemStatus> statusMap = items.stream()
            .collect(Collectors.toMap(OrderItem::getId, OrderItem::getStatus));
        List<OrderItemJpaEntity> entities = jpa.findAllById(statusMap.keySet());
        if (entities.size() != statusMap.size()) {
            throw new IllegalStateException("Some OrderItemJpaEntities not found during saveAll");
        }
        entities.forEach(e -> e.updateStatus(statusMap.get(e.getId())));
        jpa.saveAll(entities);
    }
}
