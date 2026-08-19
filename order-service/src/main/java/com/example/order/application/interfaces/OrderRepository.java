package com.example.order.application.interfaces;

import com.example.order.domain.entity.Order;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order insert(Order order);
    Optional<Order> findById(long id);
    Optional<Order> findByIdForUpdate(long id);
    List<Order> findAll();
    void save(Order order);
}
