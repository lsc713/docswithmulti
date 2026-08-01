package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.domain.entity.ProductImage;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public class ProductImageRepositoryImpl implements ProductImageRepository {

    private final ProductImageJpaRepository jpa;

    public ProductImageRepositoryImpl(ProductImageJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ProductImage save(ProductImage img) {
        return jpa.save(ProductImageJpaEntity.from(img)).toDomain();
    }

    @Override
    public List<ProductImage> findByProductId(Long productId) {
        return jpa.findByProductIdOrderBySortOrderAscIdAsc(productId).stream()
                .map(ProductImageJpaEntity::toDomain)
                .toList();
    }

    @Override
    public int nextSortOrder(Long productId) {
        Integer max = jpa.findMaxSortOrder(productId);
        return max == null ? 0 : max + 1;
    }

    @Override
    public Optional<ProductImage> findByIdAndProductId(Long id, Long productId) {
        return jpa.findByIdAndProductId(id, productId).map(ProductImageJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByIdAndProductId(Long id, Long productId) {
        jpa.deleteByIdAndProductId(id, productId);
    }

    @Override
    @Transactional
    public void updateOrder(Long productId, List<Long> imageIdsInOrder) {
        for (int i = 0; i < imageIdsInOrder.size(); i++) {
            int sortOrder = i;
            jpa.findByIdAndProductId(imageIdsInOrder.get(i), productId)
                    .ifPresent(e -> {
                        e.updateSortOrder(sortOrder);
                        jpa.save(e);
                    });
        }
    }
}
