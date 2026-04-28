package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    public Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash) {
        return jpaRepository.findByPaymentIdAndRequestHash(paymentId, requestHash)
            .map(CancelRequestJpaEntity::toDomain);
    }

    @Override
    public CancelRequest save(CancelRequest cancelRequest) {
        return jpaRepository.save(CancelRequestJpaEntity.from(cancelRequest)).toDomain();
    }

    @Override
    public List<CancelRequest> findPendingCreatedBefore(Instant before) {
        LocalDateTime beforeLdt = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        return jpaRepository.findByStatusAndCreatedAtBefore(CancelStatus.PENDING, beforeLdt)
            .stream().map(CancelRequestJpaEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<CancelRequest> findProcessingUpdatedBefore(Instant before) {
        LocalDateTime beforeLdt = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        return jpaRepository.findByStatusAndUpdatedAtBefore(CancelStatus.PROCESSING, beforeLdt)
            .stream().map(CancelRequestJpaEntity::toDomain).collect(Collectors.toList());
    }
}
