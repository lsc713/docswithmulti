package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.domain.entity.ProductStock;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RequiredArgsConstructor
public class ProductStockRepositoryImpl implements ProductStockRepository {

    private final ProductStockJpaRepository jpaRepository;

    @Override
    public ProductStock save(ProductStock stock) {
        return jpaRepository.save(ProductStockJpaEntity.from(stock)).toDomain();
    }

    @Override
    public Optional<ProductStock> findBySkuId(long skuId) {
        return jpaRepository.findBySkuId(skuId).map(ProductStockJpaEntity::toDomain);
    }

    @Override
    public Optional<ProductStock> findBySkuIdForUpdate(long skuId) {
        return jpaRepository.findBySkuIdForUpdate(skuId).map(ProductStockJpaEntity::toDomain);
    }
}
