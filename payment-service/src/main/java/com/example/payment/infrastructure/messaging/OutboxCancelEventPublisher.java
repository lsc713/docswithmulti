package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.CancelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** OUTBOX 모드: TX3 안에서 outbox 행 INSERT(같은 커밋). 발행은 스케줄러가 담당. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
public class OutboxCancelEventPublisher implements CancelEventPublisher {

    private final CancelEventOutboxRepository outboxRepository;

    @Override
    public void publish(long cancelRequestId, String payload) {
        outboxRepository.insertPending(cancelRequestId, payload);
    }
}
