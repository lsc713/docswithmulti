package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductSku;

import java.util.List;
import java.util.Optional;

/**
 * 상품 SKU 저장소 인터페이스
 *
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface ProductSkuRepository {

    ProductSku save(ProductSku sku);

    Optional<ProductSku> findById(long id);

    List<ProductSku> findAllByProductVersionId(long productVersionId);

    boolean existsBySkuCode(String skuCode);
}
