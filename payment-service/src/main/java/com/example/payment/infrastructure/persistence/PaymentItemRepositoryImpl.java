package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.PaymentItemRepository;
import com.example.payment.domain.entity.PaymentItem;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PaymentItemRepository 구현체
 */
public class PaymentItemRepositoryImpl implements PaymentItemRepository {

    private final PaymentItemJpaRepository jpaRepository;

    public PaymentItemRepositoryImpl(PaymentItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<PaymentItem> findAllByPaymentIdOrderByIdAsc(long paymentId) {
        return jpaRepository.findAllByPaymentIdOrderByIdAsc(paymentId).stream()
            .map(PaymentItemJpaEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<PaymentItem> findAllByPaymentIdForUpdate(long paymentId) {
        return jpaRepository.findAllByPaymentIdForUpdate(paymentId).stream()
            .map(PaymentItemJpaEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public void saveAll(List<PaymentItem> items) {
        jpaRepository.saveAll(items.stream()
            .map(PaymentItemJpaEntity::from)
            .collect(Collectors.toList()));
    }
}
