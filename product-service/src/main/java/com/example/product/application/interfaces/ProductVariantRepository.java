package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductAttribute;

import java.util.List;

/** 상품 변형 배선 포트: product_attribute + sku_attribute_value 저장/조회. */
public interface ProductVariantRepository {

    void saveProductAttributes(Long productId, List<ProductAttribute> attributes);

    void saveSkuVariantValues(Long skuId, List<Long> attributeValueIds);

    /** 상세 조립용: SKU × 변형 속성값 조인 (ORDER BY sku.id, attribute.id). */
    List<VariantRow> findVariantRows(Long productId);

    /** 네이티브 인터페이스 프로젝션 — 컬럼 alias 가 getter 명과 매칭. */
    interface VariantRow {
        Long getSkuId();
        String getSkuCode();
        Long getAttributeId();
        String getAttributeName();
        String getValue();
    }
}
