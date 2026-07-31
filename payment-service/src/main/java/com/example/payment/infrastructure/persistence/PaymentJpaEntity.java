package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment JPA 엔티티
 *
 * DDL: V1__create_payment_core.sql, V7__add_cancel_period_days_to_payment.sql,
 *      V18__add_order_id_to_payment.sql 기준
 * 도메인 엔티티 Payment와 분리되어 있음.
 */
@Entity
@Table(name = "payment",
    indexes = {
        @Index(name = "idx_payment_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_payment_user_id", columnList = "user_id"),
        @Index(name = "idx_payment_order_id", columnList = "order_id")
    }
)
public class PaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", unique = true, nullable = false, length = 64)
    private String paymentKey;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // PLINK-02: order-service 검증된 orderId (NOT NULL, V18). 레거시/취소 테스트 seed 행은 0L.
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "pg_type", nullable = false, length = 20)
    private String pgType;

    @Column(name = "total_amount", nullable = false, columnDefinition = "DECIMAL(19,2)")
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "cancel_period_days", nullable = false)
    private Integer cancelPeriodDays;

    @Column(name = "status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    protected PaymentJpaEntity() {
    }

    private PaymentJpaEntity(
        Long id,
        String paymentKey,
        Long merchantId,
        Long userId,
        Long orderId,
        String pgType,
        BigDecimal totalAmount,
        String currency,
        Integer cancelPeriodDays,
        PaymentStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        this.id = id;
        this.paymentKey = paymentKey;
        this.merchantId = merchantId;
        this.userId = userId;
        this.orderId = orderId;
        this.pgType = pgType;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.cancelPeriodDays = cancelPeriodDays;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 도메인 객체를 JPA 엔티티로 변환
     * id가 있으면 UPDATE, null이면 INSERT로 동작
     */
    public static PaymentJpaEntity from(Payment payment) {
        return new PaymentJpaEntity(
            payment.getId() == 0 ? null : payment.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            payment.getUserId(),
            payment.getOrderId(),
            payment.getPgType(),
            payment.getTotalAmount(),
            payment.getCurrency(),
            payment.getCancelPeriodDays(),
            payment.getStatus(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }

    /**
     * JPA 엔티티를 도메인 객체로 변환
     */
    public Payment toDomain() {
        return Payment.reconstruct(
            id,
            paymentKey,
            merchantId,
            userId,
            pgType,
            totalAmount,
            currency,
            cancelPeriodDays,
            orderId,
            status,
            createdAt,
            updatedAt
        );
    }

    // ===== Getters & Setters =====

    public Long getId() {
        return id;
    }

    public String getPaymentKey() {
        return paymentKey;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getPgType() {
        return pgType;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getCancelPeriodDays() {
        return cancelPeriodDays;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
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
