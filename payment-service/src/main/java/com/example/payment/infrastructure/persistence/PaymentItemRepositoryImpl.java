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
    public List<PaymentItem> findAllByPaymentId(Long paymentId) {
        return jpaRepository.findAllByPaymentId(paymentId).stream()
            .map(PaymentItemJpaEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public PaymentItem save(PaymentItem item) {
        PaymentItemJpaEntity entity = PaymentItemJpaEntity.from(item);
        PaymentItemJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public void saveAll(List<PaymentItem> items) {
        List<PaymentItemJpaEntity> entities = items.stream()
            .map(PaymentItemJpaEntity::from)
            .collect(Collectors.toList());
        jpaRepository.saveAll(entities);
    }
}
