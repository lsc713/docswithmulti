package com.example.riskmanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelUsageCompensationJpaRepository
    extends JpaRepository<CancelUsageCompensationJpaEntity, Long> {

    boolean existsByCancelRequestId(String cancelRequestId);
}
