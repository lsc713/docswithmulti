package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    /** cancel_request_id UK 충돌 시 no-op (복구 재실행 멱등). 취소 TX3 안에서 호출 = 메인 풀. */
    @Modifying
    @Query(value = """
        INSERT INTO cancel_event_outbox (cancel_request_id, payload, status, created_at)
        VALUES (:cancelRequestId, :payload, 'PENDING', CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE cancel_request_id = cancel_request_id
        """, nativeQuery = true)
    void insertPendingIdempotent(long cancelRequestId, String payload);
}
