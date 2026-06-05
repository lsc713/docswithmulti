package com.example.merchantlimit.application.service;

import com.example.merchantlimit.common.exception.application.MerchantCancelLimitNotFoundException;
import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import com.example.merchantlimit.common.exception.domain.MerchantNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetCancelLimitService implements GetCancelLimitUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository limitRepository;

    @Override
    @Transactional(readOnly = true)
    public Result execute(long merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        MerchantCancelLimit limit = limitRepository.findByMerchantId(merchantId)
            .orElseThrow(() -> new MerchantCancelLimitNotFoundException(merchantId));

        return new Result(merchantId, limit.getDailyLimit(), merchant.getStatus().name());
    }
}
