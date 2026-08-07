package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.MerchantReserveConfig;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 가맹점 유보 정책 원본 포트. 읽기는 active 정책만 Optional 반환(미설정 ⇒ empty ⇒ reserve=0 하위호환),
 * 쓰기는 멱등 upsert. MerchantSettlementConfigRepository 미러.
 */
public interface MerchantReserveConfigRepository {

    Optional<MerchantReserveConfig> findConfig(long merchantId);

    void upsert(long merchantId, BigDecimal reserveRate, BigDecimal reserveCap, int holdDays);
}
