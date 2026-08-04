package com.example.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** payment_event_outbox 매핑 (CancelEventOutboxJpaEntity 동형, cancelRequestId Long → paymentKey String). */
@Entity
@Table(name = "payment_event_outbox",
    indexes = { @Index(name = "idx_payment_outbox_status_created_at", columnList = "status,created_at") })
public class PaymentEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false, length = 100)
    private String paymentKey;

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

    protected PaymentEventOutboxJpaEntity() {}

    // 발행 표시는 PaymentEventOutboxJpaRepository.markPublishedBatch(native UPDATE)로 일괄 처리한다.

    public Long getId()         { return id; }
    public String getPaymentKey() { return paymentKey; }
    public String getPayload()  { return payload; }
    public String getStatus()   { return status; }
    public int getRetryCount()  { return retryCount; }
    public String getLastError() { return lastError; }
}
