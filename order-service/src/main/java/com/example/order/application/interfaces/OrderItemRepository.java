package com.example.order.application.interfaces;

import com.example.order.domain.entity.OrderItem;
import java.util.List;

public interface OrderItemRepository {
    List<OrderItem> insertAll(List<OrderItem> items);
    List<OrderItem> findAllByIdIn(List<Long> ids);
    List<OrderItem> findAllByOrderId(long orderId);
    void saveAll(List<OrderItem> items);
}
