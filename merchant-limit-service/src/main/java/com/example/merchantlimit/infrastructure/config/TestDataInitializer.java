package com.example.merchantlimit.infrastructure.config;

import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.domain.entity.Merchant;
import com.example.merchantlimit.domain.entity.MerchantCancelLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Profile("local")
@RequiredArgsConstructor
public class TestDataInitializer implements ApplicationRunner {

    private final MerchantRepository merchantRepository;
    private final MerchantCancelLimitRepository cancelLimitRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (merchantRepository.existsByMerchantKey("merchant_001")) return;

        Merchant merchant = Merchant.create("merchant_001", "테스트 가맹점", 30);
        merchant = merchantRepository.save(merchant);

        MerchantCancelLimit limit = MerchantCancelLimit.create(
            merchant.getId(), new BigDecimal("10000000"));
        cancelLimitRepository.save(limit);
    }
}
