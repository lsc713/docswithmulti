package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.ReservationStatus;
import com.example.product.domain.entity.StockReservation;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "stock_reservation")
public class StockReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false)
    private String paymentKey;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(nullable = false)
    private int qty;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected StockReservationJpaEntity() {}

    public static StockReservationJpaEntity from(StockReservation r) {
        StockReservationJpaEntity e = new StockReservationJpaEntity();
        e.id = r.getId();
        e.paymentKey = r.getPaymentKey();
        e.skuId = r.getSkuId();
        e.qty = r.getQty();
        e.unitPrice = r.getUnitPrice();
        e.status = r.getStatus();
        e.createdAt = LocalDateTime.ofInstant(r.getCreatedAt(), ZoneOffset.UTC);
        e.updatedAt = LocalDateTime.ofInstant(r.getUpdatedAt(), ZoneOffset.UTC);
        return e;
    }

    public StockReservation toDomain() {
        return StockReservation.reconstruct(id, paymentKey, skuId, qty, unitPrice, status,
                createdAt.toInstant(ZoneOffset.UTC), updatedAt.toInstant(ZoneOffset.UTC));
    }
}
