package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentItem JPA 엔티티
 *
 * DDL: V1__create_payment_core.sql + V8__align_cancel_schema.sql 기준
 * V8에서 cancelled_amount 컬럼 제거됨
 */
@Entity
@Table(name = "payment_item",
    indexes = {
        @Index(name = "idx_payment_item_payment_id", columnList = "payment_id"),
        @Index(name = "idx_payment_item_order_item_id", columnList = "order_item_id"),
        @Index(name = "idx_payment_item_product_id", columnList = "product_id")
    }
)
public class PaymentItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_auto_id", nullable = false)
    private Long productAutoId;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "item_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal itemAmount;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentItemStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected PaymentItemJpaEntity() {}

    public static PaymentItemJpaEntity from(PaymentItem item) {
        PaymentItemJpaEntity e = new PaymentItemJpaEntity();
        e.id = item.getId() == 0 ? null : item.getId();
        e.paymentId = item.getPaymentId();
        e.orderItemId = item.getOrderItemId();
        e.productId = item.getProductId();
        e.productAutoId = item.getProductAutoId();
        e.itemName = item.getItemName();
        e.itemAmount = item.getItemAmount();
        e.status = item.getStatus();
        e.createdAt = LocalDateTime.now();
        e.updatedAt = LocalDateTime.now();
        return e;
    }

    public PaymentItem toDomain() {
        return PaymentItem.reconstruct(
            id, paymentId, orderItemId, productId, productAutoId,
            itemName, itemAmount, status
        );
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public Long getOrderItemId() { return orderItemId; }
    public PaymentItemStatus getStatus() { return status; }
    public void setStatus(PaymentItemStatus status) { this.status = status; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
