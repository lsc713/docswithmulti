package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.domain.entity.ProductSku;

import java.util.List;
import java.util.Optional;

public class ProductSkuRepositoryImpl implements ProductSkuRepository {

    private final ProductSkuJpaRepository jpa;

    public ProductSkuRepositoryImpl(ProductSkuJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ProductSku save(ProductSku sku) {
        return jpa.save(ProductSkuJpaEntity.from(sku)).toDomain();
    }

    @Override
    public Optional<ProductSku> findById(long id) {
        return jpa.findById(id).map(ProductSkuJpaEntity::toDomain);
    }

    @Override
    public List<ProductSku> findAllByIdIn(List<Long> ids) {
        return jpa.findAllById(ids).stream().map(ProductSkuJpaEntity::toDomain).toList();
    }
}
