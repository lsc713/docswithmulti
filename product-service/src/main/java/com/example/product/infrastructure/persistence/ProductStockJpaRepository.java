package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductStockJpaRepository extends JpaRepository<ProductStockJpaEntity, Long> {}
