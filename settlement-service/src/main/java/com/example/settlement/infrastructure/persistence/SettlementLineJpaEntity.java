package com.example.settlement.infrastructure.persistence;

import com.example.settlement.domain.entity.SettlementLine;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "settlement_line",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_settlement_line_event", columnNames = "event_id"))
public class SettlementLineJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "payment_key", nullable = false, length = 100)
    private String paymentKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementLineJpaEntity() {}

    public SettlementLine toDomain() {
        return SettlementLine.reconstruct(id, settlementId, type, paymentKey,
            amount, eventId, occurredAt, createdAt);
    }
}
