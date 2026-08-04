package com.example.settlement.infrastructure.persistence;

import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
import com.example.settlement.domain.entity.MerchantPayoutAccount;

import java.util.Optional;

public class MerchantPayoutAccountRepositoryImpl implements MerchantPayoutAccountRepository {

    private final MerchantPayoutAccountJpaRepository jpa;

    public MerchantPayoutAccountRepositoryImpl(MerchantPayoutAccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<MerchantPayoutAccount> findActive(long merchantId) {
        return jpa.findByMerchantIdAndActiveTrue(merchantId).map(MerchantPayoutAccountJpaEntity::toDomain);
    }

    @Override
    public void upsert(long merchantId, String bankCode, String accountNumber, String holderName) {
        jpa.upsert(merchantId, bankCode, accountNumber, holderName);
    }
}
