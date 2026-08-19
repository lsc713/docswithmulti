package com.example.order.infrastructure.persistence;

import com.example.order.application.interfaces.OrderRepository;
import com.example.order.domain.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository jpa;

    @Override
    public Order insert(Order order) {
        return jpa.save(OrderJpaEntity.from(order)).toDomain();
    }

    @Override
    public Optional<Order> findById(long id) {
        return jpa.findById(id).map(OrderJpaEntity::toDomain);
    }

    @Override
    public Optional<Order> findByIdForUpdate(long id) {
        return jpa.findByIdForUpdate(id).map(OrderJpaEntity::toDomain);
    }

    @Override
    public List<Order> findAll() {
        return jpa.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
            .map(OrderJpaEntity::toDomain)
            .toList();
    }

    @Override
    public void save(Order order) {
        OrderJpaEntity entity = jpa.findById(order.getId())
            .orElseThrow(() -> new IllegalStateException(
                "OrderJpaEntity not found for id=" + order.getId()));
        entity.updateStatus(order.getStatus());
    }
}
