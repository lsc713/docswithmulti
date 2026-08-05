package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelApproval;
import com.example.payment.domain.entity.CancelApprovalStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * CancelApproval JPA 엔티티
 *
 * DDL: V20__create_cancel_approval.sql 기준.
 * 도메인 factory(request)가 createdAt/updatedAt을 설정하지 않으므로,
 * @PrePersist/@PreUpdate 로 이 엔티티가 타임스탬프를 채운다.
 */
@Entity
@Table(name = "cancel_approval",
    indexes = {
        @Index(name = "idx_cancel_approval_payment", columnList = "payment_id"),
        @Index(name = "idx_cancel_approval_status", columnList = "status")
    }
)
public class CancelApprovalJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private long paymentId;

    @Column(name = "payment_key", nullable = false, length = 64)
    private String paymentKey;

    @Column(name = "requester_user_id", nullable = false)
    private long requesterUserId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    // String(not @Enumerated CancelApprovalStatus)로 유지: Spring Data 파생 쿼리
    // (findFirstByPaymentIdAndStatus/findByStatus)가 String 인자를 받아 프로퍼티 타입과
    // 일치해야 하며, enum 타입이면 Hibernate 6에서 QueryArgumentException 발생.
    // enum↔String 변환은 fromDomain(.name())/toDomain(valueOf)에 격리 — 포트는 enum 유지.
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_role", length = 20)
    private String decidedRole;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "cancel_request_id")
    private Long cancelRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CancelApprovalJpaEntity() {}

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public static CancelApprovalJpaEntity fromDomain(CancelApproval a) {
        CancelApprovalJpaEntity e = new CancelApprovalJpaEntity();
        if (a.getId() != null) {
            e.id = a.getId();
        }
        e.paymentId = a.getPaymentId();
        e.paymentKey = a.getPaymentKey();
        e.requesterUserId = a.getRequesterUserId();
        e.reason = a.getReason();
        e.status = a.getStatus().name();
        e.decidedByUserId = a.getDecidedByUserId();
        e.decidedRole = a.getDecidedRole();
        e.decisionReason = a.getDecisionReason();
        e.cancelRequestId = a.getCancelRequestId();
        e.createdAt = a.getCreatedAt();
        e.updatedAt = a.getUpdatedAt();
        return e;
    }

    public CancelApproval toDomain() {
        return CancelApproval.reconstitute(id, paymentId, paymentKey, requesterUserId, reason,
            CancelApprovalStatus.valueOf(status),
            decidedByUserId, decidedRole, decisionReason, cancelRequestId, createdAt, updatedAt);
    }
}
