package com.example.user.infrastructure.persistence;

import com.example.user.application.interfaces.PaymentMethodRepository;
import com.example.user.domain.entity.PaymentMethod;

import java.util.List;
import java.util.Optional;

public class PaymentMethodRepositoryImpl implements PaymentMethodRepository {

    private final PaymentMethodJpaRepository jpa;

    public PaymentMethodRepositoryImpl(PaymentMethodJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<PaymentMethod> findAllByUserId(long userId) {
        return jpa.findAllByUserId(userId).stream().map(PaymentMethodJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<PaymentMethod> findByIdAndUserId(long id, long userId) {
        return jpa.findByIdAndUserId(id, userId).map(PaymentMethodJpaEntity::toDomain);
    }

    @Override
    public PaymentMethod save(PaymentMethod paymentMethod) {
        return jpa.save(PaymentMethodJpaEntity.from(paymentMethod)).toDomain();
    }

    @Override
    public void deleteById(long id) {
        jpa.deleteById(id);
    }
}
