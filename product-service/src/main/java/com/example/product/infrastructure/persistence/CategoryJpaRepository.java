package com.example.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, Long> {

    /**
     * BROWSE-01: 루트 + 모든 하위 카테고리 id (MySQL 8 재귀 CTE — QueryDSL 로 표현 불가).
     * leaf 를 넣으면 자기 자신만 반환. 파라미터 바인딩만 사용(문자열 결합 금지, T-02-02).
     */
    @Query(value = """
            WITH RECURSIVE sub AS (
                SELECT id FROM category WHERE id = :rootId
                UNION ALL
                SELECT c.id FROM category c JOIN sub ON c.parent_id = sub.id
            )
            SELECT id FROM sub
            """, nativeQuery = true)
    List<Long> findSelfAndDescendantIds(@Param("rootId") Long rootId);
}
