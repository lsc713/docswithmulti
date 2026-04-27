package com.example.payment.application.usecase;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface CreatePaymentUseCase {
    Payment create(CreatePaymentCommand command);
}
