package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestRepository;
import com.example.payment.domain.entity.CancelRequest;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequestRepository 구현체
 */
@Slf4j
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
        CancelRequestJpaEntity entity = CancelRequestJpaEntity.from(cancelRequest);
        return jpaRepository.save(entity).toDomain();
    }

    @Override
    public List<CancelRequest> findPendingCreatedBefore(Instant before) {
        log.warn("[stub] findPendingCreatedBefore: 미구현 — Task 5에서 완성");
        return List.of();
    }

    @Override
    public List<CancelRequest> findProcessingUpdatedBefore(Instant before) {
        log.warn("[stub] findProcessingUpdatedBefore: 미구현 — Task 5에서 완성");
        return List.of();
    }
}
