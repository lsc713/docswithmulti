package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cancel_request_history")
public class CancelRequestHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CancelRequestHistoryJpaEntity() {}

    public static CancelRequestHistoryJpaEntity of(long cancelRequestId, String status, String reason) {
        return of(cancelRequestId, status, reason, Instant.now());
    }

    public static CancelRequestHistoryJpaEntity of(
        long cancelRequestId, String status, String reason, Instant createdAt
    ) {
        CancelRequestHistoryJpaEntity e = new CancelRequestHistoryJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.status = status;
        e.reason = reason;
        e.createdAt = createdAt;
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
