package com.example.payment.application.usecase;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;

import java.util.List;

public interface CreatePaymentUseCase {
    record Result(Payment payment, List<PaymentItem> items) {}
    Result create(CreatePaymentCommand command);
}
