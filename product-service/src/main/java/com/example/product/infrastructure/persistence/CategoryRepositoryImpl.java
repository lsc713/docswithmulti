package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.common.exception.application.CategoryNameDuplicateException;
import com.example.product.domain.entity.Category;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpa;

    public CategoryRepositoryImpl(CategoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Category save(Category category) {
        try {
            // uk_category_parent_name(parent_key, name) 위반을 DB가 원자적으로 잡고(read-then-write TOCTOU 없음)
            // 영속성 예외를 도메인 예외로 번역 → 포트를 DB-불가지론적으로 유지.
            return jpa.saveAndFlush(CategoryJpaEntity.from(category)).toDomain();
        } catch (DataIntegrityViolationException e) {
            throw new CategoryNameDuplicateException();
        }
    }

    @Override
    public Optional<Category> findById(Long id) {
        return jpa.findById(id).map(CategoryJpaEntity::toDomain);
    }

    @Override
    public List<Category> findAll() {
        return jpa.findAll().stream().map(CategoryJpaEntity::toDomain).toList();
    }
}
