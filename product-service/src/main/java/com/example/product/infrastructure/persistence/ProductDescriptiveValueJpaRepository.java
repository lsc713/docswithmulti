package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductDescriptiveValueJpaRepository
        extends JpaRepository<ProductDescriptiveValueJpaEntity, ProductDescriptiveValueJpaEntity.Pk> {

    /**
     * 상세 조립용: 상품의 서술 속성값 조인. is_variant=FALSE 만.
     * ORDER BY a.id, av.id → specs 속성/값 순서 결정.
     */
    @Query(value = """
            SELECT a.name AS attributeName, av.value AS value
            FROM product_descriptive_value pdv
            JOIN attribute_value av   ON av.id = pdv.attribute_value_id
            JOIN attribute a          ON a.id = av.attribute_id
            JOIN product_attribute pa ON pa.product_id = pdv.product_id
                                     AND pa.attribute_id = a.id
                                     AND pa.is_variant = FALSE
            WHERE pdv.product_id = :productId
            ORDER BY a.id, av.id
            """, nativeQuery = true)
    List<com.example.product.application.interfaces.ProductVariantRepository.DescriptiveRow> findDescriptiveRows(
            @Param("productId") Long productId);
}
