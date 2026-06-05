package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductSkuJpaRepository extends JpaRepository<ProductSkuJpaEntity, Long> {

    List<ProductSkuJpaEntity> findAllByProductVersionId(long productVersionId);

    boolean existsBySkuCode(String skuCode);
}
