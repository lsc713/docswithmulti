package com.example.merchantlimit.application.interfaces;

import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import java.util.Optional;

public interface MerchantCancelLimitRepository {
    MerchantCancelLimit save(MerchantCancelLimit limit);
    Optional<MerchantCancelLimit> findByMerchantId(long merchantId);
}
