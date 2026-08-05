package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.MerchantPayoutAccount;

import java.util.Optional;

public interface MerchantPayoutAccountRepository {

    /** active=TRUE 계좌만 반환. 미설정/비활성 ⇒ empty ⇒ 승인 거부. */
    Optional<MerchantPayoutAccount> findActive(long merchantId);

    /** 계좌 설정(멱등 upsert, active=TRUE 복구). */
    void upsert(long merchantId, String bankCode, String accountNumber, String holderName);
}
