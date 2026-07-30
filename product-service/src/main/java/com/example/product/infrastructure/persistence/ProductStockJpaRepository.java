package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductStockJpaRepository extends JpaRepository<ProductStockJpaEntity, Long> {

    /**
     * 오버셀 방지 원자 차감 (D-P1-3, risk tryDeduct 미러).
     * 단일 문장 조건부 UPDATE — read-modify-write 갭이 없어 lost update/초과차감 불가.
     * @return 1 = 성공, 0 = 재고 부족(변경 없음)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE product_stock
           SET available_qty = available_qty - :qty, updated_at = CURRENT_TIMESTAMP(6)
         WHERE sku_id = :skuId AND available_qty >= :qty
        """, nativeQuery = true)
    int tryReserve(long skuId, int qty);
}
