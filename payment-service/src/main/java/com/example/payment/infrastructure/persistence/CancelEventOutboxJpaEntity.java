package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "cancel_event_outbox",
    indexes = { @Index(name = "idx_cancel_outbox_status_created_at", columnList = "status,created_at") })
public class CancelEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected CancelEventOutboxJpaEntity() {}

    // 발행 표시는 CancelEventOutboxJpaRepository.markPublishedBatch(native UPDATE)로 일괄 처리한다.

    public Long getId()              { return id; }
    public Long getCancelRequestId() { return cancelRequestId; }
    public String getPayload()       { return payload; }
    public String getStatus()        { return status; }
    public int getRetryCount()       { return retryCount; }
    public String getLastError()     { return lastError; }
}
