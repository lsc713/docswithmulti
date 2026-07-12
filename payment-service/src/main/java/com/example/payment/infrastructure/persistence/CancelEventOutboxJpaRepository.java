package com.example.payment.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    /** cancel_request_id UK 충돌 시 no-op (복구 재실행 멱등). */
    @Modifying
    @Query(value = """
        INSERT INTO cancel_event_outbox (cancel_request_id, payload, status, created_at)
        VALUES (:cancelRequestId, :payload, 'PENDING', CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE cancel_request_id = cancel_request_id
        """, nativeQuery = true)
    void insertPendingIdempotent(long cancelRequestId, String payload);

    List<CancelEventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    /** id 집합을 한 문장으로 PUBLISHED 처리 (건당 findById+save 대신 커넥션 1회). */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE cancel_event_outbox
           SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3)
         WHERE id IN (:ids)
        """, nativeQuery = true)
    int markPublishedBatch(List<Long> ids);
}
