package com.example.payment.application.service;

import com.example.payment.application.interfaces.PaymentRepository;
import com.example.payment.application.usecase.PaymentExistsQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * paymentKey 존재 여부 조회 서비스 (RST-03). repository.existsByPaymentKey 위임 — read-only.
 */
@Service
@RequiredArgsConstructor
public class PaymentExistsQueryService implements PaymentExistsQuery {

    private final PaymentRepository paymentRepository;

    @Override
    public boolean exists(String paymentKey) {
        return paymentRepository.existsByPaymentKey(paymentKey)
            || paymentRepository.existsByPaymentRequestId(paymentKey);
    }
}
