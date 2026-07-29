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
}
