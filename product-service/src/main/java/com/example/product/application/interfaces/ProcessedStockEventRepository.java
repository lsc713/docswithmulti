package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProcessedStockEvent;

/**
 * 처리된 재고 이벤트 저장소 인터페이스
 *
 * Kafka 이벤트 중복 처리 방지에 사용한다.
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface ProcessedStockEventRepository {

    boolean existsByCancelRequestId(long cancelRequestId);

    ProcessedStockEvent save(ProcessedStockEvent event);
}
