package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.domain.entity.Category;

import java.util.List;

public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpa;

    public CategoryRepositoryImpl(CategoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Category save(Category category) {
        return jpa.save(CategoryJpaEntity.from(category)).toDomain();
    }

    @Override
    public List<Category> findAll() {
        return jpa.findAll().stream().map(CategoryJpaEntity::toDomain).toList();
    }
}
