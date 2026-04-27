package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.CreateMerchantUseCase;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CreateMerchantService implements CreateMerchantUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository merchantCancelLimitRepository;

    @Override
    @Transactional
    public Merchant create(CreateMerchantCommand command) {
        Merchant merchant = merchantRepository.save(
            Merchant.create(command.merchantKey(), command.name(), command.cancelPeriodDays())
        );
        merchantCancelLimitRepository.save(
            MerchantCancelLimit.create(merchant.getId(), command.dailyLimit())
        );
        return merchant;
    }
}
