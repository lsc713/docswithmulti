package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PendingOutbox;
import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Outbox INSERT — cancel_request_id UK로 중복 방어.
 * TX3 내부에서 호출되므로 insertIfAbsent는 별도 @Transactional 없음.
 */
@Repository
public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;

    public CancelEventOutboxRepositoryImpl(CancelEventOutboxJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void insertIfAbsent(CancelRequest cancelRequest, Payment payment, List<PaymentItem> cancelledItems) {
        if (jpaRepository.existsByCancelRequestId(cancelRequest.getId())) {
            return;
        }
        String payload = buildPayload(cancelRequest, payment, cancelledItems);
        jpaRepository.save(CancelEventOutboxJpaEntity.pending(cancelRequest.getId(), payload));
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        return jpaRepository.findPendingBatch(limit)
            .stream()
            .map(e -> new PendingOutbox(e.getCancelRequestId(), e.getPayload()))
            .toList();
    }

    @Override
    @Transactional
    public void markPublished(long cancelRequestId) {
        jpaRepository.markPublished(cancelRequestId);
    }

    private String buildPayload(CancelRequest cancelRequest, Payment payment, List<PaymentItem> items) {
        String cancelledAt = cancelRequest.getCompletedAt() != null
            ? cancelRequest.getCompletedAt().toString()
            : java.time.Instant.now().toString();

        String itemsJson = items.stream()
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s}",
                i.getId(), i.getOrderItemId(), i.getItemAmount().toPlainString()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d,\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            cancelRequest.getId(),
            payment.getPaymentKey(),
            payment.getMerchantId(),
            itemsJson,
            cancelledAt
        );
    }
}
