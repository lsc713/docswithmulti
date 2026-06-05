package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ProductVersionRepositoryImpl implements ProductVersionRepository {

    private final ProductVersionJpaRepository jpaRepository;

    @Override
    public ProductVersion save(ProductVersion productVersion) {
        return jpaRepository.save(ProductVersionJpaEntity.from(productVersion)).toDomain();
    }

    @Override
    public Optional<ProductVersion> findCurrentByProductId(long productId) {
        return jpaRepository.findByProductIdAndIsCurrentTrue(productId)
                .map(ProductVersionJpaEntity::toDomain);
    }

    @Override
    public List<ProductVersion> findAllByProductId(long productId) {
        return jpaRepository.findAllByProductIdOrderByVersionDesc(productId).stream()
                .map(ProductVersionJpaEntity::toDomain)
                .toList();
    }
}
