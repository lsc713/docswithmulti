package com.example.user.presentation.dto;

import com.example.user.domain.entity.PaymentMethodType;
import jakarta.validation.constraints.NotNull;

public record PaymentMethodRequest(
    @NotNull PaymentMethodType type,
    String cardNumber, String cardCompany, String bankName, String accountNumber, boolean isDefault
) {}
