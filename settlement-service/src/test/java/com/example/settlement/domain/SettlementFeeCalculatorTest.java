package com.example.settlement.domain;

import com.example.settlement.domain.service.SettlementFeeCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 수수료/VAT/net 계산기 단위테스트 (FEE-02). I/O·Spring 없음.
 * fee = round(gross×rate,2,HALF_UP); vat = round(fee×0.10,2,HALF_UP) (이미 반올림된 fee 기준);
 * net = round(gross−cancel−fee−vat,2,HALF_UP). net 하한 없음(음수 그대로).
 */
@DisplayName("SettlementFeeCalculator (fee/vat/net · HALF_UP · scale 2)")
class SettlementFeeCalculatorTest {

    @Test
    @DisplayName("기본 산식: gross=100000, rate=0.0330 → fee 3300.00, vat 330.00, net 96370.00")
    void basicFormula() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("0.0330"));
        assertThat(r.fee()).isEqualByComparingTo("3300.00");
        assertThat(r.vat()).isEqualByComparingTo("330.00");
        assertThat(r.net()).isEqualByComparingTo("96370.00");
    }

    @Test
    @DisplayName("정확한 .xx5 tie는 HALF_UP로 올림(HALF_EVEN 아님): 10.00×0.3505=3.505 → fee 3.51")
    void halfUpExactTieRoundsUp() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("0.3505"));
        // 3.505 → HALF_UP=3.51 (HALF_EVEN이면 3.50). tie를 올림하는지 검증.
        assertThat(r.fee()).isEqualByComparingTo("3.51");
    }

    @Test
    @DisplayName("스펙 예시 경계: gross=12345, rate=0.0335 → 413.5575 → fee 413.56")
    void specBoundaryRoundsUp() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("12345"), BigDecimal.ZERO, new BigDecimal("0.0335"));
        assertThat(r.fee()).isEqualByComparingTo("413.56");
    }

    @Test
    @DisplayName("vat는 이미 반올림된 fee 기준: fee 3.51 → vat round(0.351)=0.35")
    void vatFromRoundedFee() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("0.3505"));
        assertThat(r.fee()).isEqualByComparingTo("3.51");
        assertThat(r.vat()).isEqualByComparingTo("0.35"); // 3.51×0.10=0.351 → 0.35
    }

    @Test
    @DisplayName("cancel > gross 이면 net은 음수 그대로(하한 없음)")
    void negativeNetNotFloored() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("0.0330"));
        // fee=100×0.0330=3.30, vat=0.33, net=100−500−3.30−0.33=−403.63
        assertThat(r.fee()).isEqualByComparingTo("3.30");
        assertThat(r.vat()).isEqualByComparingTo("0.33");
        assertThat(r.net()).isEqualByComparingTo("-403.63");
        assertThat(r.net().signum()).isNegative();
    }

    @Test
    @DisplayName("반환 3값 모두 scale 정확히 2")
    void allValuesScaleTwo() {
        var r = SettlementFeeCalculator.compute(
            new BigDecimal("100000"), new BigDecimal("250"), new BigDecimal("0.0330"));
        assertThat(r.fee().scale()).isEqualTo(2);
        assertThat(r.vat().scale()).isEqualTo(2);
        assertThat(r.net().scale()).isEqualTo(2);
    }
}
