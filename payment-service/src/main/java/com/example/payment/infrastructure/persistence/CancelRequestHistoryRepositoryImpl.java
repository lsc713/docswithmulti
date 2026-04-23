package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelRequestHistoryRepository;
import com.example.payment.domain.entity.CancelStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이력 기록은 항상 별도 트랜잭션으로 실행 (REQUIRES_NEW).
 * 실패해도 비즈니스 TX에 영향을 주지 않는다.
 */
@Repository
public class CancelRequestHistoryRepositoryImpl implements CancelRequestHistoryRepository {

    private final CancelRequestHistoryJpaRepository jpaRepository;

    public CancelRequestHistoryRepositoryImpl(CancelRequestHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long cancelRequestId, CancelStatus status, String reason) {
        jpaRepository.save(
            CancelRequestHistoryJpaEntity.of(cancelRequestId, status.name(), reason)
        );
    }
}
