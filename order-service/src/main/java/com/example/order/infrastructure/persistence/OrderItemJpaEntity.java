package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_item")
public class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderItemStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderItemJpaEntity() {}

    public void updateStatus(OrderItemStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public OrderItem toDomain() {
        return OrderItem.of(id, orderId, status);
    }

    public Long getId() { return id; }
}
