package com.example.settlement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_settlement_event")
public class ProcessedSettlementEventJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedSettlementEventJpaEntity() {}

    public ProcessedSettlementEventJpaEntity(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }
}
