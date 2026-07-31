package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCancelEventJpaRepository extends JpaRepository<ProcessedCancelEventJpaEntity, Long> {
    boolean existsByCancelRequestId(String cancelRequestId);
}
