package com.example.settlement.unit;

import com.example.settlement.domain.entity.MerchantReserveConfig;
import com.example.settlement.domain.service.ReserveCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReserveCalculator 순수 단위테스트(Testcontainers 불필요) — HALF_UP 반올림, cap 클램프 min(desired,cap−held),
 * cap 소진→0, config 없음→0, rate 0→0.
 */
@DisplayName("ReserveCalculator: min(round(net×rate,2,HALF_UP), max(0,cap−held))")
class ReserveCalculatorTest {

    private static MerchantReserveConfig config(String rate, String cap, int holdDays) {
        return MerchantReserveConfig.reconstruct(1L, new BigDecimal(rate), new BigDecimal(cap), holdDays);
    }

    @Test
    @DisplayName("HALF_UP 반올림: net=333.35, rate=0.1000 → 33.335 → 33.34")
    void halfUpRoundsUp() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("333.35"), Optional.of(config("0.1000", "1000000.00", 7)), BigDecimal.ZERO);
        assertThat(r).isEqualByComparingTo("33.34");
    }

    @Test
    @DisplayName("정확 배수: net=10000, rate=0.0333 → 333.00")
    void exactMultiple() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("10000.00"), Optional.of(config("0.0333", "1000000.00", 7)), BigDecimal.ZERO);
        assertThat(r).isEqualByComparingTo("333.00");
    }

    @Test
    @DisplayName("cap 클램프: desired=1000, cap=1500, held=800 → room=700 → reserve=700")
    void capClampToRoom() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("10000.00"), Optional.of(config("0.1000", "1500.00", 7)), new BigDecimal("800.00"));
        assertThat(r).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("cap 소진: held ≥ cap → room=0 → reserve=0")
    void capExhausted() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("10000.00"), Optional.of(config("0.1000", "1000.00", 7)), new BigDecimal("1000.00"));
        assertThat(r).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("config 없음 → reserve=0(하위호환)")
    void configAbsent() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("10000.00"), Optional.empty(), BigDecimal.ZERO);
        assertThat(r).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rate 0.0000 → reserve=0")
    void rateZero() {
        BigDecimal r = ReserveCalculator.compute(
                new BigDecimal("10000.00"), Optional.of(config("0.0000", "1000000.00", 7)), BigDecimal.ZERO);
        assertThat(r).isEqualByComparingTo("0");
    }
}
