package com.example.settlement.application.service;

import com.example.settlement.application.exception.InvalidReserveConfigException;
import com.example.settlement.application.exception.ReserveConfigNotFoundException;
import com.example.settlement.application.interfaces.MerchantReserveConfigRepository;
import com.example.settlement.domain.entity.MerchantReserveConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 유보 정책 조회/설정 (RCFG-01/02). SettlementConfigService 미러 — 검증 통과 시 멱등 upsert,
 * 조회는 미설정 시 ReserveConfigNotFoundException(404). 검증 위반은 InvalidReserveConfigException(400).
 * ADMIN 인가는 배포 시 NetworkPolicy 게이트웨이 ingress 제한(objective, SettlementConfigController 동일 클래스).
 */
@Service
@RequiredArgsConstructor
public class ReserveConfigService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final MerchantReserveConfigRepository repo;

    @Transactional
    public void setConfig(long merchantId, BigDecimal rate, BigDecimal cap, int holdDays) {
        if (rate == null) {
            throw new InvalidReserveConfigException("reserveRate는 필수입니다.");
        }
        if (rate.signum() < 0 || rate.compareTo(ONE) >= 0) {
            throw new InvalidReserveConfigException("reserveRate는 0 이상 1 미만이어야 합니다: " + rate);
        }
        if (rate.scale() > 4) {
            throw new InvalidReserveConfigException("reserveRate 소수 자릿수는 4 이하여야 합니다: " + rate);
        }
        if (cap == null) {
            throw new InvalidReserveConfigException("reserveCap는 필수입니다.");
        }
        if (cap.signum() < 0) {
            throw new InvalidReserveConfigException("reserveCap는 0 이상이어야 합니다: " + cap);
        }
        if (holdDays < 0) {
            throw new InvalidReserveConfigException("holdDays는 0 이상이어야 합니다: " + holdDays);
        }
        repo.upsert(merchantId, rate, cap, holdDays);
    }

    @Transactional(readOnly = true)
    public MerchantReserveConfig getConfig(long merchantId) {
        return repo.findConfig(merchantId)
            .orElseThrow(() -> new ReserveConfigNotFoundException(merchantId));
    }
}
