package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.StockReleaseRetryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class StockReleaseRetryRepositoryImpl implements StockReleaseRetryRepository {

    private final StockReleaseRetryJpaRepository jpaRepository;

    public StockReleaseRetryRepositoryImpl(StockReleaseRetryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void enqueue(String paymentKey, String itemsJson) {
        // payment_key UK 멱등: 같은 실패 요청의 재적재는 무시(따닥/재시도 안전).
        if (jpaRepository.existsByPaymentKey(paymentKey)) {
            return;
        }
        try {
            jpaRepository.save(StockReleaseRetryJpaEntity.pending(paymentKey, itemsJson));
        } catch (DataIntegrityViolationException race) {
            // exists 체크와 save 사이 경합 — UK가 막았으므로 정상(멱등).
        }
    }

    @Override
    public List<PendingRelease> findDueForRetry(LocalDateTime now) {
        return jpaRepository.findDueForRetry(now).stream()
            .map(e -> new PendingRelease(
                e.getId(), e.getPaymentKey(), e.getItemsJson(), e.getAttemptCount()))
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
    public void markRetryLater(long id, int newAttemptCount, LocalDateTime nextRetryAt, String lastError) {
        jpaRepository.findById(id).ifPresent(e -> {
            e.markFailed(newAttemptCount, nextRetryAt, lastError);
            jpaRepository.save(e);
        });
    }

    @Override
    public void exhaust(long id, int finalAttemptCount, String lastError) {
        jpaRepository.findById(id).ifPresent(e -> {
            e.exhaust(finalAttemptCount, lastError);
            jpaRepository.save(e);
        });
    }
}
