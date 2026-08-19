package com.example.product.application.interfaces;

import com.example.product.domain.entity.Category;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository {
    /** INSERT 후 생성된 id가 채워진 Category 반환. */
    Category save(Category category);

    Optional<Category> findById(Long id);

    /** leaf부터 root까지 조회해 level 오름차순으로 반환. */
    List<Category> findPathByLeafId(Long leafId);

    /** 트리 조립용 전체 조회. */
    List<Category> findAll();
}
