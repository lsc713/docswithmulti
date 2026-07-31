package com.example.product.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_cancel_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_processed_cancel_event_cancel_request_id",
        columnNames = "cancel_request_id"))
public class ProcessedCancelEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64, unique = true)
    private String cancelRequestId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedCancelEventJpaEntity() {}

    public static ProcessedCancelEventJpaEntity of(String cancelRequestId) {
        ProcessedCancelEventJpaEntity e = new ProcessedCancelEventJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.processedAt = Instant.now();
        return e;
    }
}
