package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    List<ProductJpaEntity> findAllByCategoryId(long categoryId);

    List<ProductJpaEntity> findAllByMerchantId(long merchantId);
}
