package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedStockEventJpaRepository extends JpaRepository<ProcessedStockEventJpaEntity, Long> {

    boolean existsByCancelRequestId(long cancelRequestId);
}
