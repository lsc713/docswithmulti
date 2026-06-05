package com.example.user.application.interfaces;

import com.example.user.domain.entity.PaymentMethod;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository {
    List<PaymentMethod> findAllByUserId(long userId);
    Optional<PaymentMethod> findByIdAndUserId(long id, long userId);
    PaymentMethod save(PaymentMethod paymentMethod);
    void deleteById(long id);
}
