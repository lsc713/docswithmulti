package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.domain.entity.ProductAttribute;

import java.util.List;

public class ProductVariantRepositoryImpl implements ProductVariantRepository {

    private final ProductAttributeJpaRepository productAttributeJpa;
    private final SkuAttributeValueJpaRepository skuValueJpa;

    public ProductVariantRepositoryImpl(ProductAttributeJpaRepository productAttributeJpa,
                                        SkuAttributeValueJpaRepository skuValueJpa) {
        this.productAttributeJpa = productAttributeJpa;
        this.skuValueJpa = skuValueJpa;
    }

    @Override
    public void saveProductAttributes(Long productId, List<ProductAttribute> attributes) {
        productAttributeJpa.saveAll(attributes.stream().map(ProductAttributeJpaEntity::from).toList());
    }

    @Override
    public void saveSkuVariantValues(Long skuId, List<Long> attributeValueIds) {
        skuValueJpa.saveAll(attributeValueIds.stream()
                .map(vid -> SkuAttributeValueJpaEntity.of(skuId, vid))
                .toList());
    }

    @Override
    public List<VariantRow> findVariantRows(Long productId) {
        return skuValueJpa.findVariantRows(productId);
    }
}
