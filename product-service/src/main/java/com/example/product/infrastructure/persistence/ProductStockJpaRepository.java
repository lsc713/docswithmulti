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

    /**
     * 재고 복원 (W2, D-P1-4, risk tryRestore 미러). release 조건부 전이(affected=1) 뒤에만 호출.
     * 상태전이가 복원의 유일 트리거이므로 여기선 무조건 복원(무조건 1행 매칭).
     * @return 1 = 복원 성공, 0 = 대상 sku 없음(불변식 위반)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE product_stock
           SET available_qty = available_qty + :qty, updated_at = CURRENT_TIMESTAMP(6)
         WHERE sku_id = :skuId
        """, nativeQuery = true)
    int restore(long skuId, int qty);
}
