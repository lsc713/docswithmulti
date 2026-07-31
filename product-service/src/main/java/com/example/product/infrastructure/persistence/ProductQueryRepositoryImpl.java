package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/** JpaRepository 들에 위임하는 읽기 전용 어댑터. */
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private final ProductJpaRepository productJpa;
    private final ProductSkuJpaRepository skuJpa;
    private final CategoryJpaRepository categoryJpa;

    public ProductQueryRepositoryImpl(ProductJpaRepository productJpa,
                                      ProductSkuJpaRepository skuJpa,
                                      CategoryJpaRepository categoryJpa) {
        this.productJpa = productJpa;
        this.skuJpa = skuJpa;
        this.categoryJpa = categoryJpa;
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

    @Override
    public List<Long> descendantCategoryIds(Long rootId) {
        return categoryJpa.findSelfAndDescendantIds(rootId);
    }

    @Override
    public Page<Product> findByCategoryIds(List<Long> categoryIds, int page, int size) {
        if (categoryIds.isEmpty()) {
            return Page.empty(PageRequest.of(page, size)); // 파생 쿼리 empty-IN 방어
        }
        return productJpa
                .findByCategoryIdInOrderByCreatedAtDescIdDesc(categoryIds, PageRequest.of(page, size))
                .map(ProductJpaEntity::toDomain);
    }
}
