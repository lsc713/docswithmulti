package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.common.exception.domain.MerchantSuspendedException;
import com.example.merchantlimit.common.exception.domain.InvalidLimitAmountException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MerchantLimitDomainService")
class MerchantLimitDomainServiceTest {

    private final MerchantLimitDomainService sut = new MerchantLimitDomainService();

    @Test
    @DisplayName("ACTIVE 가맹점 한도 변경 성공")
    void update_limit_for_active_merchant() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        sut.updateLimit(merchant, limit, BigDecimal.valueOf(8_000_000));

        assertThat(limit.getDailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
    }

    @Test
    @DisplayName("SUSPENDED 가맹점 한도 변경 시 예외")
    void update_limit_for_suspended_merchant_throws() {
        var merchant = MerchantFixture.suspended();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        assertThatThrownBy(() -> sut.updateLimit(merchant, limit, BigDecimal.valueOf(8_000_000)))
            .isInstanceOf(MerchantSuspendedException.class);
    }

    @Test
    @DisplayName("0원 한도로 변경 시 예외")
    void update_limit_to_zero_throws() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(merchant.getId());

        assertThatThrownBy(() -> sut.updateLimit(merchant, limit, BigDecimal.ZERO))
            .isInstanceOf(InvalidLimitAmountException.class);
    }
}
