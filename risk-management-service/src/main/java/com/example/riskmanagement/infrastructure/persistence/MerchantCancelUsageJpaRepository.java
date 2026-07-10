package com.example.riskmanagement.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface MerchantCancelUsageJpaRepository
    extends JpaRepository<MerchantCancelUsageJpaEntity, Long> {

    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDate(long merchantId, LocalDate kstDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM MerchantCancelUsageJpaEntity e WHERE e.merchantId = :merchantId AND e.kstDate = :kstDate")
    Optional<MerchantCancelUsageJpaEntity> findByMerchantIdAndKstDateForUpdate(long merchantId, LocalDate kstDate);

    /** (merchant_id, kst_date) 행이 없으면 used=0 으로 생성(멱등). 충돌 시 no-op. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO merchant_cancel_usage
            (merchant_id, kst_date, daily_limit, used_amount, created_at, updated_at)
        VALUES (:merchantId, :kstDate, :dailyLimit, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE merchant_id = merchant_id
        """, nativeQuery = true)
    int ensureRow(long merchantId, LocalDate kstDate, BigDecimal dailyLimit);

    /**
     * 원자 조건부 차감. WHERE 절이 도메인 규칙(used + amount <= daily_limit)을 그대로 옮긴 것.
     * 단일 문장이라 read-modify-write 갭이 없어 lost update/초과차감/데드락 없음.
     * @return 1 = 성공, 0 = 한도 초과(변경 없음)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE merchant_cancel_usage
           SET used_amount = used_amount + :amount, updated_at = CURRENT_TIMESTAMP(6)
         WHERE merchant_id = :merchantId AND kst_date = :kstDate
           AND used_amount + :amount <= daily_limit
        """, nativeQuery = true)
    int tryDeduct(long merchantId, LocalDate kstDate, BigDecimal amount);
}
