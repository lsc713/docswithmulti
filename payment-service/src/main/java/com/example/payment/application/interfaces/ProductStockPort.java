package com.example.payment.application.interfaces;

import java.math.BigDecimal;
import java.util.List;

/**
 * product-service 동기 재고 예약/해제 포트 (D-P2-5).
 * 생성 경로에서 payment TX 밖(앞)에서 호출한다.
 */
public interface ProductStockPort {

    /** SKU 재고 동기 예약. 재고 부족·장애 시 예외 전파(fail-closed). paymentKey는 멱등 키. */
    List<ReservedItem> reserve(String paymentKey, List<Item> items);

    /** 예약 재고 해제(보상). 실패는 ProductServiceException. */
    void release(String paymentKey, List<Item> items);

    record Item(long productId, long skuId, int qty) {
        public Item(long skuId, int qty) {
            this(0, skuId, qty);
        }
    }

    record ReservedItem(long skuId, long productId, BigDecimal unitPrice, int quantity) {}
}
