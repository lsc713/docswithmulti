package com.example.product.application.usecase;

import java.util.List;

/**
 * 취소 이벤트로 SKU 재고를 복원하는 유스케이스 (RST-02).
 *
 * <p>취소된 items 의 skuId+qty 만큼 재고를 release. 멱등 테이블·부분취소 하드닝은 03-02,
 * retry/DLQ 는 03-03 — 여기서는 happy path 위임만.
 */
public interface ProcessCancelledStockUseCase {

    void execute(Command command);

    record Command(String cancelRequestId, String paymentKey, List<Item> items) {
        public record Item(long skuId, int qty) {}
    }
}
