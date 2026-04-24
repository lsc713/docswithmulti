package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.OrderRepository;
import com.example.order.domain.entity.Order;
import lombok.RequiredArgsConstructor;
import java.util.Optional;

@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpa;

    @Override
    public Optional<Order> findByIdForUpdate(long id) {
        return jpa.findByIdForUpdate(id).map(OrderJpaEntity::toDomain);
    }

    @Override
    public void save(Order order) {
        jpa.findById(order.getId())
            .ifPresent(e -> e.updateStatus(order.getStatus()));
    }
}
