package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.domain.entity.ProductSku;

public class ProductSkuRepositoryImpl implements ProductSkuRepository {

    private final ProductSkuJpaRepository jpa;

    public ProductSkuRepositoryImpl(ProductSkuJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ProductSku save(ProductSku sku) {
        return jpa.save(ProductSkuJpaEntity.from(sku)).toDomain();
    }
}
