package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.PaymentEventOutboxRepository;

import java.util.List;

/**
 * 전 경로(insert/find/mark)를 단일 메인 풀 JpaRepository 로 위임한다
 * (cancel 임플과 달리 전용 JdbcTemplate 풀 없음 — poll-only, 지연 비민감).
 */
public class PaymentEventOutboxRepositoryImpl implements PaymentEventOutboxRepository {

    private final PaymentEventOutboxJpaRepository jpaRepository;

    public PaymentEventOutboxRepositoryImpl(PaymentEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertPending(String paymentKey, String payload) {
        // 생성 TX 안 — 메인 풀 유지(payment/payment_item 커밋과 원자적)
        jpaRepository.insertPendingIdempotent(paymentKey, payload);
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository.findPendingBatch(limit).stream()
            .map(e -> new PendingOutbox(e.getId(), e.getPaymentKey(), e.getPayload(), e.getRetryCount()))
            .toList();
    }

    @Override
    public void markPublished(List<Long> outboxIds) {
        if (outboxIds.isEmpty()) {
            return; // WHERE id IN () 방지
        }
        jpaRepository.markPublishedBatch(outboxIds);
    }

    @Override
    public void bumpRetry(long id, String lastError) {
        jpaRepository.bumpRetry(id, truncate(lastError));
    }

    @Override
    public void markDead(long id, String lastError) {
        jpaRepository.markDead(id, truncate(lastError));
    }

    private static String truncate(String error) {
        return error != null ? error.substring(0, Math.min(500, error.length())) : null;
    }
}
