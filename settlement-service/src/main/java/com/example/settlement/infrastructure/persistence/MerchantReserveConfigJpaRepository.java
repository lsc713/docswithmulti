package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface MerchantReserveConfigJpaRepository
    extends JpaRepository<MerchantReserveConfigJpaEntity, Long> {

    Optional<MerchantReserveConfigJpaEntity> findByMerchantIdAndActiveTrue(long merchantId);

    /** 유보 정책 설정(멱등 upsert). 이미 있으면 rate/cap/holdDays 갱신 + active 복구(MerchantSettlementConfigJpaRepository.upsert 미러). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO merchant_reserve_config
            (merchant_id, reserve_rate, reserve_cap, hold_days, active, created_at, updated_at)
        VALUES (:merchantId, :reserveRate, :reserveCap, :holdDays, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE reserve_rate = :reserveRate, reserve_cap = :reserveCap,
            hold_days = :holdDays, active = TRUE, updated_at = CURRENT_TIMESTAMP(3)
        """, nativeQuery = true)
    int upsert(@Param("merchantId") long merchantId,
               @Param("reserveRate") BigDecimal reserveRate,
               @Param("reserveCap") BigDecimal reserveCap,
               @Param("holdDays") int holdDays);
}
