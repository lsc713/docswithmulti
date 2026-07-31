package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.domain.entity.Product;

import java.util.List;
import java.util.Optional;

/** JpaRepository 들에 위임하는 읽기 전용 어댑터. */
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private final ProductJpaRepository productJpa;
    private final ProductSkuJpaRepository skuJpa;

    public ProductQueryRepositoryImpl(ProductJpaRepository productJpa, ProductSkuJpaRepository skuJpa) {
        this.productJpa = productJpa;
        this.skuJpa = skuJpa;
    }

    @Override
    public Optional<Product> findProductById(Long id) {
        return productJpa.findById(id).map(ProductJpaEntity::toDomain);
    }

    @Override
    public List<SkuStock> findSkuStock(Long productId) {
        return skuJpa.findSkuStockByProductId(productId).stream()
                .map(v -> new SkuStock(v.getSkuCode(), v.getOptionSummary(), v.getAvailableQty()))
                .toList();
    }
}
