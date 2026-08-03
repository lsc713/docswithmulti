package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttributeValueJpaRepository extends JpaRepository<AttributeValueJpaEntity, Long> {

    List<AttributeValueJpaEntity> findByIdIn(Collection<Long> ids);
}
