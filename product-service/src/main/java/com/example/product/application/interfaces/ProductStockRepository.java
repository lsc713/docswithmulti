package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductStock;

import java.util.List;

public interface ProductStockRepository {
    record Adjustment(long skuId, int qty) {}

    /** 초기 재고 seed. */
    void save(ProductStock stock);

    /**
     * 오버셀 방지 원자 차감 (D-P1-3, risk tryDeduct 미러).
     * 단일 문장 조건부 UPDATE `WHERE available_qty >= :qty` — read-modify-write 갭 없음.
     * @return 입력 순서별 affected rows. 1 = 차감 성공, 0 = 재고 부족
     */
    int[] tryReserveAll(List<Adjustment> adjustments);

    /**
     * 재고 일괄 복원 (W2). release 조건부 전이 뒤에만 호출 → over-release 불가.
     * @return 입력 순서별 affected rows. 1 = 복원 성공, 0 = 대상 sku 없음
     */
    int[] restoreAll(List<Adjustment> adjustments);
}
