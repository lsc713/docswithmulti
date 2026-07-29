package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequest Spring Data JPA Repository
 */
public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {

    Optional<CancelRequestJpaEntity> findByPaymentIdAndRequestHash(Long paymentId, String requestHash);

    // D-idem: dedup_key(V15 generated column, ik:/ch: prefix)로 조회. request_hash는 더 이상 유일하지 않음.
    Optional<CancelRequestJpaEntity> findByPaymentIdAndDedupKey(Long paymentId, String dedupKey);

    // pending-recovery: PENDING + createdAt 기준
    List<CancelRequestJpaEntity> findByStatusAndCreatedAtBefore(CancelStatus status, LocalDateTime before);

    // processing-recovery: PROCESSING + updatedAt 기준
    List<CancelRequestJpaEntity> findByStatusAndUpdatedAtBefore(CancelStatus status, LocalDateTime before);

    /**
     * pg_retry_count 원자 UPDATE. read-modify-write 경쟁(객체 mutation+save) 제거 —
     * MerchantCancelUsageJpaRepository.tryDeduct/tryRestore 컨벤션 이식(D-04).
     * clearAutomatically=true 필수(1차 캐시 stale 방지) — 호출자는 반드시 재조회해서 사용할 것.
     * @return 1 = 성공, 0 = 대상 행 없음
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE cancel_request
           SET pg_retry_count = pg_retry_count + 1, updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = :id
        """, nativeQuery = true)
    int incrementPgRetryCount(@Param("id") long id);

    /**
     * PROCESSING → FAILED 조건부 원자 UPDATE. incrementPgRetryCount와 동일한 D-04 패턴(이중 보상 방지, CR-03).
     * clearAutomatically=true 필수(1차 캐시 stale 방지).
     * @return 1 = 성공, 0 = 대상 행 없음(이미 PROCESSING이 아님 — 다른 스레드가 선점)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE cancel_request
           SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP(3)
         WHERE id = :id AND status = 'PROCESSING'
        """, nativeQuery = true)
    int compareAndSetFailed(@Param("id") long id);
}
