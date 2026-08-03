package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductAttributeJpaRepository
        extends JpaRepository<ProductAttributeJpaEntity, ProductAttributeJpaEntity.Pk> {
}
