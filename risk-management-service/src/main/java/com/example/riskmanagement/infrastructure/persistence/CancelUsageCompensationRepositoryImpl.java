package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.CancelUsageCompensationRepository;
import com.example.riskmanagement.domain.entity.CancelUsageCompensation;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CancelUsageCompensationRepositoryImpl implements CancelUsageCompensationRepository {

    private final CancelUsageCompensationJpaRepository jpa;

    @Override
    public CancelUsageCompensation save(CancelUsageCompensation compensation) {
        return jpa.save(CancelUsageCompensationJpaEntity.from(compensation)).toDomain();
    }

    @Override
    public boolean existsByCancelRequestId(String cancelRequestId) {
        return jpa.existsByCancelRequestId(cancelRequestId);
    }
}
