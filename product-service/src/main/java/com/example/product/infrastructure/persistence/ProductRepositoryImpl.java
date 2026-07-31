package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.domain.entity.Product;

public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpa;

    public ProductRepositoryImpl(ProductJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Product save(Product product) {
        return jpa.save(ProductJpaEntity.from(product)).toDomain();
    }
}
