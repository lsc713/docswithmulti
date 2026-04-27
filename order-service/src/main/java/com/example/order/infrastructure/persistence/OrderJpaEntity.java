package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.Order;
import com.example.order.domain.entity.OrderStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderJpaEntity() {}

    public static OrderJpaEntity from(Order order) {
        OrderJpaEntity e = new OrderJpaEntity();
        e.userId = order.getUserId();
        e.status = order.getStatus();
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Order toDomain() {
        return Order.of(id, userId != null ? userId : 0L, status);
    }

    public Long getId() { return id; }
}
