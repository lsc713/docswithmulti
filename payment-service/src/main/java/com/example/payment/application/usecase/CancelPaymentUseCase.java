package com.example.payment.application.usecase;

import com.example.payment.application.service.CancelPaymentCommand;
import com.example.payment.domain.entity.CancelRequest;

public interface CancelPaymentUseCase {
    CancelRequest cancel(CancelPaymentCommand command);
}
