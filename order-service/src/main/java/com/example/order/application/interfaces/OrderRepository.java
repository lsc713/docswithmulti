package com.example.order.application.interfaces;

import com.example.order.domain.entity.Order;
import java.util.Optional;

public interface OrderRepository {
    Order insert(Order order);
    Optional<Order> findById(long id);
    Optional<Order> findByIdForUpdate(long id);
    void save(Order order);
}
