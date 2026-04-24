package com.example.riskmanagement.infrastructure.persistence;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class CancelUsageHistoryRepositoryImpl implements CancelUsageHistoryRepository {

    private final CancelUsageHistoryJpaRepository jpa;

    @Override
    public CancelUsageHistory save(CancelUsageHistory history) {
        return jpa.save(CancelUsageHistoryJpaEntity.from(history)).toDomain();
    }

    @Override
    public Optional<CancelUsageHistory> findByCancelRequestId(String cancelRequestId) {
        return jpa.findByCancelRequestId(cancelRequestId)
            .map(CancelUsageHistoryJpaEntity::toDomain);
    }
}
