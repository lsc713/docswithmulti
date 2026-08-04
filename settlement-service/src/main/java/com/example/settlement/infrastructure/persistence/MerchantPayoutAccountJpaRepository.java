package com.example.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MerchantPayoutAccountJpaRepository
    extends JpaRepository<MerchantPayoutAccountJpaEntity, Long> {

    Optional<MerchantPayoutAccountJpaEntity> findByMerchantIdAndActiveTrue(long merchantId);

    /** 계좌 설정(멱등 upsert). 이미 있으면 갱신 + active 복구(mirror MerchantSettlementConfigJpaRepository.upsert). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO merchant_payout_account
            (merchant_id, bank_code, account_number, holder_name, active, created_at, updated_at)
        VALUES (:merchantId, :bankCode, :accountNumber, :holderName, TRUE,
                CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE
            bank_code = :bankCode, account_number = :accountNumber, holder_name = :holderName,
            active = TRUE, updated_at = CURRENT_TIMESTAMP(3)
        """, nativeQuery = true)
    int upsert(@Param("merchantId") long merchantId,
               @Param("bankCode") String bankCode,
               @Param("accountNumber") String accountNumber,
               @Param("holderName") String holderName);
}
