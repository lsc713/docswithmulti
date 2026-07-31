package com.example.product.application.interfaces;

import com.example.product.domain.entity.Category;

import java.util.List;

public interface CategoryRepository {
    /** INSERT 후 생성된 id가 채워진 Category 반환. */
    Category save(Category category);

    /** 트리 조립용 전체 조회. */
    List<Category> findAll();
}
