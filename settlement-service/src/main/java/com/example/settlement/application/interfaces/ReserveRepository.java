package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.Reserve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 유보금 포트. currentHeld = 가맹점 누적 held(cap 계산), insertHeld = 승인 시 HELD 행 durable write(자체 TX),
 * findBySettlementId = 조회. PayoutRepository 미러.
 */
public interface ReserveRepository {

    /** 가맹점 누적 held (HELD+RELEASING) Σamount. 0행이면 0(null-safe). */
    BigDecimal currentHeld(long merchantId);

    /** 승인 시 HELD 유보 행 INSERT — non-@Transactional approve 에서 호출되는 독립 durable write. */
    void insertHeld(long settlementId, long merchantId, BigDecimal amount, LocalDate holdUntil, String transferRef);

    Optional<Reserve> findBySettlementId(long settlementId);
}
