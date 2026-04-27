package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CompensationRetryRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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

    @Override
    public List<PendingCompensation> findDueForRetry(Instant now) {
        return jpaRepository.findDueForRetry(now).stream()
            .map(e -> new PendingCompensation(
                e.getId(), e.getCancelRequestIdAsLong(),
                e.getMerchantId(), e.getRestoreAmount(), e.getAttemptCount()))
            .toList();
    }

    @Override
    public void markDone(long id) {
        jpaRepository.findById(id).ifPresent(e -> {
            e.markDone();
            jpaRepository.save(e);
        });
    }

    @Override
    public void markRetryLater(long id, int newAttemptCount, Instant nextRetryAt, String lastError) {
        jpaRepository.findById(id).ifPresent(e -> {
            e.markFailed(newAttemptCount, nextRetryAt, lastError);
            jpaRepository.save(e);
        });
    }

    @Override
    public void exhaust(long id, int finalAttemptCount, String lastError) {
        jpaRepository.findById(id).ifPresent(e -> {
            e.exhaust(lastError);
            jpaRepository.save(e);
        });
    }
}
