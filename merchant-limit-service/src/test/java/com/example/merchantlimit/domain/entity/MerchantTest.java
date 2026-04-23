package com.example.merchantlimit.domain.entity;

import com.example.merchantlimit.domain.exception.MerchantSuspendedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Merchant 도메인 엔티티")
class MerchantTest {

    @Test
    @DisplayName("ACTIVE 가맹점 생성")
    void create_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);

        assertThat(merchant.getMerchantKey()).isEqualTo("mct_001");
        assertThat(merchant.getName()).isEqualTo("패션몰A");
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(merchant.getCancelPeriodDays()).isEqualTo(90);
    }

    @Test
    @DisplayName("ACTIVE → INACTIVE 비활성화")
    void deactivate_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.deactivate();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.INACTIVE);
    }

    @Test
    @DisplayName("ACTIVE → SUSPENDED 정지")
    void suspend_active_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.suspend();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    @DisplayName("INACTIVE → ACTIVE 재활성화")
    void activate_inactive_merchant() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.deactivate();
        merchant.activate();
        assertThat(merchant.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
    }

    @Test
    @DisplayName("SUSPENDED 가맹점은 한도 변경 불가")
    void suspended_merchant_cannot_change_limit() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        merchant.suspend();
        assertThatThrownBy(merchant::validateLimitChangeable)
            .isInstanceOf(MerchantSuspendedException.class);
    }

    @Test
    @DisplayName("ACTIVE 가맹점은 한도 변경 가능")
    void active_merchant_can_change_limit() {
        Merchant merchant = Merchant.create("mct_001", "패션몰A", 90);
        assertThatNoException().isThrownBy(merchant::validateLimitChangeable);
    }
}
