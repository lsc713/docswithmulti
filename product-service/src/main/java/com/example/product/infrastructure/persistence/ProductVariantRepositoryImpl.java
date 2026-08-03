package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.domain.entity.ProductAttribute;

import java.util.List;

public class ProductVariantRepositoryImpl implements ProductVariantRepository {

    private final ProductAttributeJpaRepository productAttributeJpa;
    private final SkuAttributeValueJpaRepository skuValueJpa;
    private final ProductDescriptiveValueJpaRepository descriptiveValueJpa;

    public ProductVariantRepositoryImpl(ProductAttributeJpaRepository productAttributeJpa,
                                        SkuAttributeValueJpaRepository skuValueJpa,
                                        ProductDescriptiveValueJpaRepository descriptiveValueJpa) {
        this.productAttributeJpa = productAttributeJpa;
        this.skuValueJpa = skuValueJpa;
        this.descriptiveValueJpa = descriptiveValueJpa;
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
    public void saveProductDescriptiveValues(Long productId, List<Long> attributeValueIds) {
        descriptiveValueJpa.saveAll(attributeValueIds.stream()
                .map(vid -> ProductDescriptiveValueJpaEntity.of(productId, vid))
                .toList());
    }

    @Override
    public List<VariantRow> findVariantRows(Long productId) {
        return skuValueJpa.findVariantRows(productId);
    }

    @Override
    public List<DescriptiveRow> findDescriptiveRows(Long productId) {
        return descriptiveValueJpa.findDescriptiveRows(productId);
    }
}
