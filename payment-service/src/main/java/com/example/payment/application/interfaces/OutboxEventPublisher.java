package com.example.payment.application.interfaces;

/**
 * Outbox 이벤트 발행 포트.
 * 구현체: infrastructure/messaging/KafkaOutboxPublisher
 */
public interface OutboxEventPublisher {
    void publish(long cancelRequestId, String payload) throws Exception;
}
