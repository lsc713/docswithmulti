package com.example.order.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * cancel_restore_dlq 매핑 (V4, Phase 1 product 미러). 필드 매핑만, 도메인 로직 없음.
 * 상태전이/upsert 는 CancelRestoreDlqJpaRepository 의 native 쿼리로 처리한다.
 */
@Entity
@Table(name = "cancel_restore_dlq",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_cancel_restore_dlq_cancel_request_id",
        columnNames = "cancel_request_id"),
    indexes = @Index(name = "idx_cancel_restore_dlq_status_updated", columnList = "status,updated_at"))
public class CancelRestoreDlqJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false, length = 64, unique = true)
    private String cancelRequestId;

    @Column(name = "leg", nullable = false, length = 16)
    private String leg;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CancelRestoreDlqJpaEntity() {}

    public Long getId()              { return id; }
    public String getCancelRequestId() { return cancelRequestId; }
    public String getLeg()           { return leg; }
    public String getPayload()       { return payload; }
    public int getRetryCount()       { return retryCount; }
    public int getAttemptCount()     { return attemptCount; }
    public String getStatus()        { return status; }
    public String getLastError()     { return lastError; }
}
