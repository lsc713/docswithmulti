package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * payment_event_outbox — insert(TX 안)와 find/mark(폴러) 모두 메인 datasource(JPA)에서 실행한다.
 * cancel 아웃박스와 달리 전용 Hikari 풀을 쓰지 않으므로 OutboxDataSourceConfig 는 diff 0.
 */
public interface PaymentEventOutboxJpaRepository
    extends JpaRepository<PaymentEventOutboxJpaEntity, Long> {

    /** payment_key UK 충돌 시 no-op (생성 재시도 멱등). 생성 TX 안에서 호출 = 메인 풀/원자적. */
    @Modifying
    @Query(value = """
        INSERT INTO payment_event_outbox (payment_key, payload, status, created_at)
        VALUES (:paymentKey, :payload, 'PENDING', CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE payment_key = payment_key
        """, nativeQuery = true)
    void insertPendingIdempotent(@Param("paymentKey") String paymentKey, @Param("payload") String payload);

    @Query(value = """
        SELECT * FROM payment_event_outbox
         WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit
        """, nativeQuery = true)
    List<PaymentEventOutboxJpaEntity> findPendingBatch(@Param("limit") int limit);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payment_event_outbox
           SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3)
         WHERE id IN (:ids)
        """, nativeQuery = true)
    void markPublishedBatch(@Param("ids") List<Long> ids);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE payment_event_outbox
           SET retry_count = retry_count + 1, last_error = :err
         WHERE id = :id
        """, nativeQuery = true)
    void bumpRetry(@Param("id") long id, @Param("err") String lastError);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE payment_event_outbox SET status = 'DEAD', last_error = :err WHERE id = :id",
        nativeQuery = true)
    void markDead(@Param("id") long id, @Param("err") String lastError);
}
