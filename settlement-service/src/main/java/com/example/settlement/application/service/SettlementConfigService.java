package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.MerchantSettlementConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 정산 요율 조회/설정 (FEE-01). 조회는 active 요율만 Optional(미설정 ⇒ empty ⇒ 계산기 미호출 = finalize 유예).
 * 설정은 feeRate 검증(0 ≤ rate < 1, scale ≤ 4 — ASVS V5) 후 멱등 upsert. ADMIN 인가는 배포 시 NetworkPolicy로 차단(objective).
 */
@Service
@RequiredArgsConstructor
public class SettlementConfigService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final MerchantSettlementConfigRepository repo;

    @Transactional(readOnly = true)
    public Optional<BigDecimal> getRate(long merchantId) {
        return repo.findRate(merchantId);
    }

    @Transactional
    public void setRate(long merchantId, BigDecimal feeRate) {
        if (feeRate == null) {
            throw new IllegalArgumentException("feeRate는 필수입니다.");
        }
        if (feeRate.signum() < 0 || feeRate.compareTo(ONE) >= 0) {
            throw new IllegalArgumentException("feeRate는 0 이상 1 미만이어야 합니다: " + feeRate);
        }
        if (feeRate.scale() > 4) {
            throw new IllegalArgumentException("feeRate 소수 자릿수는 4 이하여야 합니다: " + feeRate);
        }
        repo.upsert(merchantId, feeRate);
    }
}
