package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductStock;

public interface ProductStockRepository {
    /** 초기 재고 seed. */
    void save(ProductStock stock);

    /**
     * 오버셀 방지 원자 차감 (D-P1-3, risk tryDeduct 미러).
     * 단일 문장 조건부 UPDATE `WHERE available_qty >= :qty` — read-modify-write 갭 없음.
     * @return 1 = 차감 성공, 0 = 재고 부족(변경 없음)
     */
    int tryReserve(long skuId, int qty);
}
