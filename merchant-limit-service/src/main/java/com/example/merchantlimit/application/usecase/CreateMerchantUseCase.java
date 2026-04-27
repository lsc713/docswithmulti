package com.example.merchantlimit.application.usecase;

import com.example.merchantlimit.application.service.CreateMerchantCommand;
import com.example.merchantlimit.domain.entity.Merchant;

public interface CreateMerchantUseCase {
    Merchant create(CreateMerchantCommand command);
}
