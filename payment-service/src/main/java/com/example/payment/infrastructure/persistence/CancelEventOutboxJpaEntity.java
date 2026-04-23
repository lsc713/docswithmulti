package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "cancel_event_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_outbox_cancel_request_id",
        columnNames = "cancel_request_id"
    )
)
public class CancelEventOutboxJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected CancelEventOutboxJpaEntity() {}

    public static CancelEventOutboxJpaEntity pending(long cancelRequestId, String payload) {
        CancelEventOutboxJpaEntity e = new CancelEventOutboxJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.payload = payload;
        e.status = "PENDING";
        e.createdAt = Instant.now();
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
