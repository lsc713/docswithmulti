package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "failed_kafka_event",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_failed_kafka_cancel_request_id",
        columnNames = "cancel_request_id"
    )
)
public class FailedKafkaEventJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_request_id", nullable = false)
    private Long cancelRequestId;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FailedKafkaEventJpaEntity() {}

    public static FailedKafkaEventJpaEntity pending(long cancelRequestId, String topic, String payload) {
        var e = new FailedKafkaEventJpaEntity();
        e.cancelRequestId = cancelRequestId;
        e.topic = topic;
        e.payload = payload;
        e.status = "PENDING";
        e.retryCount = 0;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    public Long getCancelRequestId() { return cancelRequestId; }
    public String getTopic()          { return topic; }
    public String getPayload()        { return payload; }
    public String getStatus()         { return status; }
    public int    getRetryCount()     { return retryCount; }
}
