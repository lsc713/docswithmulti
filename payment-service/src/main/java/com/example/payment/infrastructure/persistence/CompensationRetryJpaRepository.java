package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompensationRetryJpaRepository
    extends JpaRepository<CompensationRetryJpaEntity, Long> {
}
