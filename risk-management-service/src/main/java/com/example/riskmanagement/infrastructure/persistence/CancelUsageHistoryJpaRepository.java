package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CancelUsageHistoryJpaRepository
    extends JpaRepository<CancelUsageHistoryJpaEntity, Long> {

    Optional<CancelUsageHistoryJpaEntity> findByCancelRequestId(String cancelRequestId);
}
