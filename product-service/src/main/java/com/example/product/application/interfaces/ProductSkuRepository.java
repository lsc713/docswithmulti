package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductSku;

import java.util.List;
import java.util.Optional;

public interface ProductSkuRepository {
    /** INSERT 후 생성된 id가 채워진 ProductSku 반환. */
    ProductSku save(ProductSku sku);

    Optional<ProductSku> findById(long id);

    List<ProductSku> findAllByIdIn(List<Long> ids);
}
