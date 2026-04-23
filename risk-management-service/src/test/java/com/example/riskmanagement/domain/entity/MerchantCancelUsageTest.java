package com.example.riskmanagement.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantCancelUsage")
class MerchantCancelUsageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @Test
    @DisplayName("deduct — used_amount 증가")
    void deduct_increases_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        usage.deduct(BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
        assertThat(usage.remaining()).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));
    }

    @Test
    @DisplayName("restore — used_amount 감소")
    void restore_decreases_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        usage.restore(BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("restore — 음수 방지 (0 이하로 내려가지 않음)")
    void restore_does_not_go_below_zero() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));
        usage.restore(BigDecimal.valueOf(500_000)); // 복원액 > 소진액
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("updateDailyLimit — daily_limit 변경")
    void updateDailyLimit_changes_limit() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        usage.updateDailyLimit(BigDecimal.valueOf(8_000_000));
        assertThat(usage.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
    }
}
