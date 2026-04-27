package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.PendingOutbox;
import com.example.payment.infrastructure.messaging.KafkaOutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Outbox PENDING 건을 Kafka에 발행하고 PUBLISHED로 마킹.
 * 건별로 독립 처리 — 한 건 실패가 나머지를 막지 않음.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisherService {

    private static final int BATCH_SIZE = 1000;

    private final CancelEventOutboxRepository outboxRepository;
    private final KafkaOutboxPublisher kafkaOutboxPublisher;

    public void publish() {
        List<PendingOutbox> pending = outboxRepository.findPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.info("[outbox-publisher] 발행 대상 {}건", pending.size());

        for (PendingOutbox outbox : pending) {
            try {
                kafkaOutboxPublisher.publish(outbox.cancelRequestId(), outbox.payload());
                outboxRepository.markPublished(outbox.cancelRequestId());
            } catch (Exception e) {
                log.error("[outbox-publisher] 발행 실패 — 다음 주기 재시도. cancelRequestId={}, error={}",
                    outbox.cancelRequestId(), e.getMessage());
            }
        }
    }
}
