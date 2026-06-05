package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductVersionJpaRepository extends JpaRepository<ProductVersionJpaEntity, Long> {

    Optional<ProductVersionJpaEntity> findByProductIdAndIsCurrentTrue(long productId);

    List<ProductVersionJpaEntity> findAllByProductIdOrderByVersionDesc(long productId);
}
