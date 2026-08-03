package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SkuAttributeValueJpaRepository
        extends JpaRepository<SkuAttributeValueJpaEntity, SkuAttributeValueJpaEntity.Pk> {

    /**
     * 상세 조립용: 상품의 SKU × 변형 속성값 조인. is_variant=true 만.
     * ORDER BY s.id, a.id → variantOptions/variant 순서 결정.
     */
    @Query(value = """
            SELECT s.id AS skuId, s.sku_code AS skuCode,
                   a.id AS attributeId, a.name AS attributeName, av.value AS value
            FROM product_sku s
            JOIN sku_attribute_value sav ON sav.sku_id = s.id
            JOIN attribute_value av      ON av.id = sav.attribute_value_id
            JOIN attribute a             ON a.id = av.attribute_id
            JOIN product_attribute pa    ON pa.product_id = s.product_id
                                        AND pa.attribute_id = a.id
                                        AND pa.is_variant = TRUE
            WHERE s.product_id = :productId
            ORDER BY s.id, a.id
            """, nativeQuery = true)
    List<com.example.product.application.interfaces.ProductVariantRepository.VariantRow> findVariantRows(
            @Param("productId") Long productId);
}
