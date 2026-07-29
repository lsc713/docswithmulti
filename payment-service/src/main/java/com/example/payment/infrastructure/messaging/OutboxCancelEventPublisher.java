package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** OUTBOX 모드: TX3 안에서 outbox 행 INSERT(같은 커밋). 발행은 스케줄러가 담당(권위).
 *  커밋 성공 후 RTopic wake로 스케줄러를 즉시 깨운다 — 비권위 저지연 트리거, 실패해도 poll backstop이 커버. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
public class OutboxCancelEventPublisher implements CancelEventPublisher {

    private static final String WAKE_TOPIC = "cancel-outbox-wake";

    private final CancelEventOutboxRepository outboxRepository;
    private final RedissonClient redissonClient;

    @Override
    public void publish(long cancelRequestId, String payload) {
        outboxRepository.insertPending(cancelRequestId, payload); // TX3 커밋과 원자적
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            wake(cancelRequestId);
                        }
                    });
        } else {
            wake(cancelRequestId); // 트랜잭션 밖(복구 등) — 즉시
        }
    }

    private void wake(long cancelRequestId) {
        try {
            redissonClient.getTopic(WAKE_TOPIC).publish(cancelRequestId);
        } catch (Exception e) {
            // wake는 비권위 — 실패해도 poll backstop이 커버. 로그만.
            log.debug("[outbox] wake 발사 실패(무해, poll이 커버) cancelRequestId={}", cancelRequestId);
        }
    }
}
