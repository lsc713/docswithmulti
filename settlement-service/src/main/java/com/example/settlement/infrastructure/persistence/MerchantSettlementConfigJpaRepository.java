package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface MerchantSettlementConfigJpaRepository
    extends JpaRepository<MerchantSettlementConfigJpaEntity, Long> {

    Optional<MerchantSettlementConfigJpaEntity> findByMerchantIdAndActiveTrue(long merchantId);

    /** 요율 설정(멱등 upsert). 이미 있으면 fee_rate 갱신 + active 복구(mirror SettlementJpaRepository.ensureRow). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO merchant_settlement_config
            (merchant_id, fee_rate, active, created_at, updated_at)
        VALUES (:merchantId, :feeRate, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE fee_rate = :feeRate, active = TRUE, updated_at = CURRENT_TIMESTAMP(3)
        """, nativeQuery = true)
    int upsert(@Param("merchantId") long merchantId, @Param("feeRate") BigDecimal feeRate);
}
