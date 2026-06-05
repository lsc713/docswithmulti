package com.example.product.application.interfaces;

import com.example.product.domain.entity.Category;

import java.util.List;
import java.util.Optional;

/**
 * 카테고리 저장소 인터페이스
 *
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface CategoryRepository {

    Category save(Category category);

    Optional<Category> findById(long id);

    List<Category> findAll();

    boolean existsByParentId(long parentId);

    void deleteById(long id);
}
