package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.PaymentItem;
import com.example.payment.domain.entity.PaymentItemStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentItem JPA 엔티티
 *
 * DDL: V1__create_payment_core.sql 기준
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

    @Column(name = "cancelled_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal cancelledAmount;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentItemStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected PaymentItemJpaEntity() {
    }

    private PaymentItemJpaEntity(
        Long paymentId,
        Long orderItemId,
        Long productId,
        Long productAutoId,
        String itemName,
        BigDecimal itemAmount,
        BigDecimal cancelledAmount,
        PaymentItemStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.paymentId = paymentId;
        this.orderItemId = orderItemId;
        this.productId = productId;
        this.productAutoId = productAutoId;
        this.itemName = itemName;
        this.itemAmount = itemAmount;
        this.cancelledAmount = cancelledAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 도메인 객체를 JPA 엔티티로 변환
     * (createdAt/updatedAt는 BaseEntity 필드이므로 별도 설정 불필요)
     */
    public static PaymentItemJpaEntity from(PaymentItem item) {
        PaymentItemJpaEntity entity = new PaymentItemJpaEntity(
            item.getPaymentId(),
            item.getOrderItemId(),
            item.getProductId(),
            item.getProductAutoId(),
            item.getItemName(),
            item.getItemAmount(),
            item.getCancelledAmount(),
            item.getStatus(),
            LocalDateTime.now(),  // createdAt는 JPA가 자동 설정
            LocalDateTime.now()   // updatedAt는 JPA가 자동 설정
        );
        return entity;
    }

    /**
     * JPA 엔티티를 도메인 객체로 변환
     */
    public PaymentItem toDomain() {
        return PaymentItem.reconstruct(
            id,
            paymentId,
            orderItemId,
            productId,
            productAutoId,
            itemName,
            itemAmount,
            cancelledAmount,
            status
        );
    }

    // ===== Getters & Setters =====

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getProductAutoId() {
        return productAutoId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getItemAmount() {
        return itemAmount;
    }

    public BigDecimal getCancelledAmount() {
        return cancelledAmount;
    }

    public void setCancelledAmount(BigDecimal cancelledAmount) {
        this.cancelledAmount = cancelledAmount;
    }

    public PaymentItemStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentItemStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
