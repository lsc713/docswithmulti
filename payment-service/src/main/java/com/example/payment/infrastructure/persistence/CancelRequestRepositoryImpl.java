package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * CancelRequestRepository 구현체
 */
public class CancelRequestRepositoryImpl implements CancelRequestRepository {

    private final CancelRequestJpaRepository jpaRepository;

    public CancelRequestRepositoryImpl(CancelRequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public CancelRequest save(CancelRequest cancelRequest) {
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(cancelRequest);
        CancelRequestJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<CancelRequest> findById(Long id) {
        return jpaRepository.findById(id)
            .map(CancelRequestJpaEntity::toDomain);
    }

    @Override
    public List<CancelRequest> findAllByPaymentId(Long paymentId) {
        return jpaRepository.findAllByPaymentId(paymentId).stream()
            .map(CancelRequestJpaEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<CancelRequest> findCompletedByPaymentId(Long paymentId) {
        return jpaRepository.findCompletedByPaymentId(paymentId).stream()
            .map(CancelRequestJpaEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<CancelRequest> findStuckProcessingRequests(LocalDateTime before) {
        return jpaRepository.findStuckProcessingRequests(before).stream()
            .map(CancelRequestJpaEntity::toDomain)
            .collect(Collectors.toList());
    }
}
