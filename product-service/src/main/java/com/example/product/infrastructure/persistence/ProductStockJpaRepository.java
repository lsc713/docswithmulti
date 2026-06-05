package com.example.product.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductStockJpaRepository extends JpaRepository<ProductStockJpaEntity, Long> {

    Optional<ProductStockJpaEntity> findBySkuId(long skuId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductStockJpaEntity s WHERE s.skuId = :skuId")
    Optional<ProductStockJpaEntity> findBySkuIdForUpdate(@Param("skuId") long skuId);
}
