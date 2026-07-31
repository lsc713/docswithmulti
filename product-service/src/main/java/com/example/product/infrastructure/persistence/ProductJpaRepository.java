package com.example.product.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    /**
     * BROWSE-01: 하위 카테고리 상품을 최신순 페이징.
     * IdDesc 2차 키 — 같은 마이크로초 created_at 동률을 결정적으로 깨서 순서 단언이 flaky 하지 않게 한다.
     */
    Page<ProductJpaEntity> findByCategoryIdInOrderByCreatedAtDescIdDesc(List<Long> categoryIds, Pageable pageable);
}
