package com.example.product.domain.entity;

import java.time.LocalDateTime;

/**
 * 처리된 재고 이벤트 도메인 엔티티
 *
 * Kafka payment.cancelled 이벤트 중복 처리를 방지한다.
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class ProcessedStockEvent {

    private Long id;
    private long cancelRequestId;
    private LocalDateTime createdAt;

    private ProcessedStockEvent(Long id, long cancelRequestId, LocalDateTime createdAt) {
        this.id = id;
        this.cancelRequestId = cancelRequestId;
        this.createdAt = createdAt;
    }

    /**
     * 신규 이벤트 처리 기록 생성
     */
    public static ProcessedStockEvent of(long cancelRequestId) {
        return new ProcessedStockEvent(null, cancelRequestId, LocalDateTime.now());
    }

    /**
     * DB 조회 후 재구성
     */
    public static ProcessedStockEvent reconstruct(Long id, long cancelRequestId, LocalDateTime createdAt) {
        return new ProcessedStockEvent(id, cancelRequestId, createdAt);
    }

    public Long getId() { return id; }
    public long getCancelRequestId() { return cancelRequestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
