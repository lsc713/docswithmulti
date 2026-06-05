package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.domain.entity.ProductSku;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ProductSkuRepositoryImpl implements ProductSkuRepository {

    private final ProductSkuJpaRepository jpaRepository;

    @Override
    public ProductSku save(ProductSku sku) {
        return jpaRepository.save(ProductSkuJpaEntity.from(sku)).toDomain();
    }

    @Override
    public Optional<ProductSku> findById(long id) {
        return jpaRepository.findById(id).map(ProductSkuJpaEntity::toDomain);
    }

    @Override
    public List<ProductSku> findAllByProductVersionId(long productVersionId) {
        return jpaRepository.findAllByProductVersionId(productVersionId).stream()
                .map(ProductSkuJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsBySkuCode(String skuCode) {
        return jpaRepository.existsBySkuCode(skuCode);
    }
}
