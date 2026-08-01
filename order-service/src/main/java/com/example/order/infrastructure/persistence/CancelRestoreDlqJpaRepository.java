package com.example.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * cancel_restore_dlq JPA 접근 (Phase 1 product 미러, native SQL 허용).
 * upsert/상태전이는 MySQL native(INSERT..ON DUPLICATE KEY / UPDATE), findRedrivable 은 projection.
 */
public interface CancelRestoreDlqJpaRepository extends JpaRepository<CancelRestoreDlqJpaEntity, Long> {

    /**
     * cancel_request_id UK 멱등 upsert. 기존 행은 retry_count/last_error/updated_at 만 갱신 —
     * status(RESOLVED/DEAD) 는 절대 부활시키지 않는다.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO cancel_restore_dlq
            (cancel_request_id, leg, payload, retry_count, attempt_count, status,
             first_failed_at, last_error, created_at, updated_at)
        VALUES (:cancelRequestId, :leg, :payload, :retryCount, 0, 'PENDING',
             CURRENT_TIMESTAMP(3), :lastError, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE
            retry_count = :retryCount,
            last_error  = :lastError,
            updated_at  = CURRENT_TIMESTAMP(3)
        """, nativeQuery = true)
    void upsertPending(@Param("cancelRequestId") String cancelRequestId,
                       @Param("leg") String leg,
                       @Param("payload") String payload,
                       @Param("retryCount") int retryCount,
                       @Param("lastError") String lastError);

    @Query(value = """
        SELECT cancel_request_id AS cancelRequestId, payload AS payload, attempt_count AS attemptCount
        FROM cancel_restore_dlq
        WHERE status = 'PENDING' AND updated_at <= :updatedBefore
        ORDER BY updated_at ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<RedrivableRow> findRedrivable(@Param("updatedBefore") Instant updatedBefore,
                                       @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = "UPDATE cancel_restore_dlq SET status = 'RESOLVED', updated_at = CURRENT_TIMESTAMP(3) "
        + "WHERE cancel_request_id = :cancelRequestId", nativeQuery = true)
    void markResolved(@Param("cancelRequestId") String cancelRequestId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE cancel_restore_dlq SET attempt_count = attempt_count + 1, last_error = :lastError, "
        + "updated_at = CURRENT_TIMESTAMP(3) WHERE cancel_request_id = :cancelRequestId", nativeQuery = true)
    void bumpAttempt(@Param("cancelRequestId") String cancelRequestId, @Param("lastError") String lastError);

    @Modifying
    @Transactional
    @Query(value = "UPDATE cancel_restore_dlq SET status = 'DEAD', attempt_count = attempt_count + 1, "
        + "last_error = :lastError, updated_at = CURRENT_TIMESTAMP(3) "
        + "WHERE cancel_request_id = :cancelRequestId", nativeQuery = true)
    void markDead(@Param("cancelRequestId") String cancelRequestId, @Param("lastError") String lastError);

    /** findRedrivable projection (native → Redrivable record 매핑용). */
    interface RedrivableRow {
        String getCancelRequestId();
        String getPayload();
        int getAttemptCount();
    }
}
