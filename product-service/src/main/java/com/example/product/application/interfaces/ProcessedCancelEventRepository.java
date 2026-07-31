package com.example.product.application.interfaces;

/**
 * payment.cancelled 멱등 게이트 (RST-02, D-P3-2/D-P3-4).
 *
 * <p>cancel_request_id UK로 at-least-once 중복 이벤트를 no-op 처리. order-service
 * ProcessedCancelEventRepository와 동형 시그니처.
 */
public interface ProcessedCancelEventRepository {
    boolean existsByCancelRequestId(String cancelRequestId);
    void save(String cancelRequestId);
}
