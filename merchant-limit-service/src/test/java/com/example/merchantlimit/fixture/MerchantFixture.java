package com.example.merchantlimit.fixture;

import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantStatus;

public class MerchantFixture {

    public static Merchant active() {
        return Merchant.reconstruct(1L, "mct_001", "패션몰A", MerchantStatus.ACTIVE, 90);
    }

    /** 영속성 insert 테스트용 — id 없는 신규 가맹점 (IDENTITY 채번 대상). */
    public static Merchant newMerchant() {
        return Merchant.create("mct_001", "패션몰A", 90);
    }

    public static Merchant suspended() {
        return Merchant.reconstruct(2L, "mct_002", "정지몰B", MerchantStatus.SUSPENDED, 90);
    }

    public static Merchant inactive() {
        return Merchant.reconstruct(3L, "mct_003", "비활성몰C", MerchantStatus.INACTIVE, 90);
    }
}
