package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface SettlementLineJpaRepository extends JpaRepository<SettlementLineJpaEntity, Long> {

    /** event_id UK가 중복 적재를 DB 레벨에서 차단(멱등 권위 가드). 충돌 시 DataIntegrityViolationException. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO settlement_line
            (settlement_id, type, payment_key, amount, event_id, occurred_at, created_at)
        VALUES (:settlementId, :type, :paymentKey, :amount, :eventId, :occurredAt, CURRENT_TIMESTAMP(3))
        """, nativeQuery = true)
    int insertLine(@Param("settlementId") long settlementId,
                   @Param("type") String type,
                   @Param("paymentKey") String paymentKey,
                   @Param("amount") BigDecimal amount,
                   @Param("eventId") String eventId,
                   @Param("occurredAt") Instant occurredAt);

    List<SettlementLineJpaEntity> findBySettlementIdOrderByOccurredAtAscIdAsc(long settlementId);
}
