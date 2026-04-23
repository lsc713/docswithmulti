package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CompensationRetryRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class CompensationRetryRepositoryImpl implements CompensationRetryRepository {

    private final CompensationRetryJpaRepository jpaRepository;

    public CompensationRetryRepositoryImpl(CompensationRetryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(long cancelRequestId, long merchantId, BigDecimal restoreAmount) {
        jpaRepository.save(
            CompensationRetryJpaEntity.pending(cancelRequestId, merchantId, restoreAmount)
        );
    }
}
