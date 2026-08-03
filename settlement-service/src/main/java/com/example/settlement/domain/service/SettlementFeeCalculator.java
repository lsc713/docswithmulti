package com.example.settlement.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 수수료/VAT/net 순수 계산기 (FEE-02). I/O·상태·Spring 없음 — 도메인 산식만.
 *
 * <pre>
 *   fee = round(gross × feeRate, 2, HALF_UP)
 *   vat = round(fee × 0.10, 2, HALF_UP)   // 이미 반올림된 fee 기준(gross 아님)
 *   net = round(gross − cancel − fee − vat, 2, HALF_UP)   // 하한 없음(음수 가능)
 * </pre>
 *
 * 요율 미설정 처리는 여기 없음 — 호출부가 Optional 요율을 확인하고, 요율이 있을 때만 compute를 호출한다
 * (deferral은 config 조회에 있음). Phase 2는 결과를 영속화하지 않음(FINALIZE는 Phase 3).
 */
public final class SettlementFeeCalculator {

    private static final BigDecimal VAT_RATE = new BigDecimal("0.10");
    private static final int SCALE = 2;

    private SettlementFeeCalculator() {}

    public record Result(BigDecimal fee, BigDecimal vat, BigDecimal net) {}

    public static Result compute(BigDecimal gross, BigDecimal cancel, BigDecimal feeRate) {
        BigDecimal fee = gross.multiply(feeRate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal vat = fee.multiply(VAT_RATE).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(cancel).subtract(fee).subtract(vat)
            .setScale(SCALE, RoundingMode.HALF_UP);
        return new Result(fee, vat, net);
    }
}
