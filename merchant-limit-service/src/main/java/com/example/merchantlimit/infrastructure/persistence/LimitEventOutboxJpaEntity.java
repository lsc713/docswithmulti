package com.example.merchantlimit.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "limit_event_outbox",
    indexes = {
        @Index(name = "idx_limit_outbox_status_created_at", columnList = "status,created_at")
    })
public class LimitEventOutboxJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "payload", nullable = false, columnDefinition = "JSON")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected LimitEventOutboxJpaEntity() {}

    public static LimitEventOutboxJpaEntity pending(long merchantId, String payload) {
        LimitEventOutboxJpaEntity e = new LimitEventOutboxJpaEntity();
        e.merchantId = merchantId;
        e.payload = payload;
        e.status = "PENDING";
        e.createdAt = Instant.now();
        return e;
    }

    public void markPublished() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
    }

    public Long getId()         { return id; }
    public Long getMerchantId() { return merchantId; }
    public String getPayload()  { return payload; }
    public String getStatus()   { return status; }
}
