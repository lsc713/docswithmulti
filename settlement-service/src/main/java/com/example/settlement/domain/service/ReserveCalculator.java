package com.example.settlement.domain.service;

import com.example.settlement.domain.entity.MerchantReserveConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 유보 산정. 순수 도메인 헬퍼 — I/O 없음, 단위 테스트 가능(SettlementWeek 미러).
 *
 * <p>reserve = min(round(net×rate, 2, HALF_UP), max(0, cap − currentHeld)).
 * config 없음/비활성(empty) 또는 rate 0 ⇒ ZERO ⇒ payout=net(하위호환). cap 소진 ⇒ room 0 ⇒ ZERO.
 */
public final class ReserveCalculator {

    private ReserveCalculator() {}

    public static BigDecimal compute(BigDecimal net, Optional<MerchantReserveConfig> config, BigDecimal currentHeld) {
        if (config.isEmpty() || config.get().getReserveRate().signum() == 0) {
            return BigDecimal.ZERO;
        }
        MerchantReserveConfig c = config.get();
        BigDecimal desired = net.multiply(c.getReserveRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal room = c.getReserveCap().subtract(currentHeld).max(BigDecimal.ZERO);
        return desired.min(room);
    }
}
