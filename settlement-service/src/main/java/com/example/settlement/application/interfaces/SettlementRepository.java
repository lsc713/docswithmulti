package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.Settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository {

    /** (merchant_id, period_start) 헤더가 없으면 OPEN으로 생성(멱등). 존재 시 no-op. */
    int ensureRow(long merchantId, LocalDate periodStart, LocalDate periodEnd);

    /** ensureRow로 보장된 헤더의 id 조회. */
    long findId(long merchantId, LocalDate periodStart);

    /** cancel_amount 원자 증분(UPDATE ... SET cancel_amount = cancel_amount + :amount). */
    int addCancelAmount(long merchantId, LocalDate periodStart, BigDecimal amount);

    /** gross_amount 원자 증분(UPDATE ... SET gross_amount = gross_amount + :amount). */
    int addGrossAmount(long merchantId, LocalDate periodStart, BigDecimal amount);

    /** status가 null이면 전체, 아니면 해당 status로 필터. */
    List<Settlement> findByMerchant(long merchantId, String status);

    Optional<Settlement> findById(long id);
}
