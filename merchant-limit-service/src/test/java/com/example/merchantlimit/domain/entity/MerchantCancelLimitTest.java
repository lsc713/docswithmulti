package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.common.exception.domain.InvalidLimitAmountException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantCancelLimit 도메인 엔티티")
class MerchantCancelLimitTest {

    @Test
    @DisplayName("정상 한도로 생성")
    void create_with_valid_limit() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(5_000_000));

        assertThat(limit.getMerchantId()).isEqualTo(1L);
        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("0원 한도 생성 시 예외")
    void create_with_zero_limit_throws() {
        assertThatThrownBy(() -> MerchantCancelLimit.create(1L, BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }

    @Test
    @DisplayName("음수 한도 생성 시 예외")
    void create_with_negative_limit_throws() {
        assertThatThrownBy(() -> MerchantCancelLimit.create(1L, BigDecimal.valueOf(-1)))
            .isInstanceOf(InvalidLimitAmountException.class);
    }

    @Test
    @DisplayName("한도 변경 — 유효한 금액")
    void update_with_valid_limit() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(3_000_000));
        limit.update(BigDecimal.valueOf(5_000_000));
        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    @DisplayName("한도 변경 — 0원이면 예외")
    void update_with_zero_throws() {
        MerchantCancelLimit limit = MerchantCancelLimit.create(1L, BigDecimal.valueOf(3_000_000));
        assertThatThrownBy(() -> limit.update(BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }
}
