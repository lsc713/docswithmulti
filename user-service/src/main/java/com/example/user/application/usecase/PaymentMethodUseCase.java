package com.example.user.application.usecase;

import com.example.user.domain.entity.PaymentMethod;
import com.example.user.domain.entity.PaymentMethodType;
import java.util.List;

public interface PaymentMethodUseCase {
    record CreateCommand(PaymentMethodType type, String cardNumber, String cardCompany,
                         String bankName, String accountNumber, boolean isDefault) {}

    List<PaymentMethod> getPaymentMethods(long userId);
    PaymentMethod create(long userId, CreateCommand command);
    void delete(long userId, long paymentMethodId);
}
