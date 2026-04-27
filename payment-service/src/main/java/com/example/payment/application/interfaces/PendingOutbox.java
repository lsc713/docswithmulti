package com.example.payment.application.interfaces;

/**
 * OutboxPublisherService가 사용하는 발행 대기 Outbox 데이터.
 * JPA 엔티티를 application 레이어로 노출하지 않기 위한 경량 레코드.
 */
public record PendingOutbox(long cancelRequestId, String payload) {}
