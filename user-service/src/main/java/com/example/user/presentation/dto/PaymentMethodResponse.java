package com.example.user.presentation.dto;

import com.example.user.domain.entity.PaymentMethod;

public record PaymentMethodResponse(Long id, String type, String cardNumber, String cardCompany,
                                     String bankName, String accountNumber, boolean isDefault) {
    public static PaymentMethodResponse from(PaymentMethod pm) {
        return new PaymentMethodResponse(pm.getId(), pm.getType().name(), pm.getCardNumber(),
                pm.getCardCompany(), pm.getBankName(), pm.getAccountNumber(), pm.isDefault());
    }
}
