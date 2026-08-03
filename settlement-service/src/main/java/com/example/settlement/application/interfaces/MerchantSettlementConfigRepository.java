package com.example.settlement.application.interfaces;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 가맹점 정산 요율 원본 포트 (FEE-01). 읽기는 active 요율만 Optional 반환(요율 미설정 ⇒ empty ⇒ finalize 유예),
 * 쓰기는 멱등 upsert. 기본값 0%를 반환하지 않는다(empty와 0%는 다른 의미).
 */
public interface MerchantSettlementConfigRepository {

    Optional<BigDecimal> findRate(long merchantId);

    void upsert(long merchantId, BigDecimal feeRate);
}
