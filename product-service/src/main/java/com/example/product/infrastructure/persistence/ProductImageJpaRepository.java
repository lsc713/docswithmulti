package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductImageJpaRepository extends JpaRepository<ProductImageJpaEntity, Long> {

    List<ProductImageJpaEntity> findByProductIdOrderBySortOrderAscIdAsc(Long productId);

    Optional<ProductImageJpaEntity> findByIdAndProductId(Long id, Long productId);

    void deleteByIdAndProductId(Long id, Long productId);

    @Query("SELECT MAX(i.sortOrder) FROM ProductImageJpaEntity i WHERE i.productId = :productId")
    Integer findMaxSortOrder(@Param("productId") Long productId);
}
