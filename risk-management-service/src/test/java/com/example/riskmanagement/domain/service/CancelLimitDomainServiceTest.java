package com.example.riskmanagement.domain.service;

import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CancelLimitDomainService")
class CancelLimitDomainServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private final CancelLimitDomainService sut = new CancelLimitDomainService();

    @Test
    @DisplayName("validateAndDeduct — 한도 내이면 차감 성공")
    void validateAndDeduct_within_limit_succeeds() {
        MerchantCancelUsage usage = MerchantCancelUsage.create(1L, TODAY, BigDecimal.valueOf(5_000_000));
        sut.validateAndDeduct(usage, BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
    }

    @Test
    @DisplayName("validateAndDeduct — 한도 초과 시 MerchantCancelLimitExceededException")
    void validateAndDeduct_over_limit_throws() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(4_800_000));
        assertThatThrownBy(() -> sut.validateAndDeduct(usage, BigDecimal.valueOf(300_000)))
            .isInstanceOf(MerchantCancelLimitExceededException.class);
    }

    @Test
    @DisplayName("applyCompensation — used_amount 복원")
    void applyCompensation_restores_usedAmount() {
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        sut.applyCompensation(usage, BigDecimal.valueOf(300_000));
        assertThat(usage.getUsedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
