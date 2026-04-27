package com.example.order.infrastructure.persistence;

import com.example.order.domain.entity.OrderItem;
import com.example.order.domain.entity.OrderItemStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "order_item")
public class OrderItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "item_name", length = 100)
    private String itemName;

    @Column(name = "price", columnDefinition = "DECIMAL(19,2)")
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderItemStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OrderItemJpaEntity() {}

    public static OrderItemJpaEntity from(OrderItem item) {
        OrderItemJpaEntity e = new OrderItemJpaEntity();
        e.orderId = item.getOrderId();
        e.productId = item.getProductId();
        e.itemName = item.getItemName();
        e.price = item.getPrice();
        e.status = item.getStatus();
        e.createdAt = Instant.now();
        e.updatedAt = Instant.now();
        return e;
    }

    public void updateStatus(OrderItemStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public OrderItem toDomain() {
        return OrderItem.of(id, orderId,
            productId != null ? productId : 0L,
            itemName != null ? itemName : "",
            price != null ? price : BigDecimal.ZERO,
            status);
    }

    public Long getId() { return id; }
}
