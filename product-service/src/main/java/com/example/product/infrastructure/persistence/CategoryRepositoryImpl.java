package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.domain.entity.Category;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;

    @Override
    public Category save(Category category) {
        return jpaRepository.save(CategoryJpaEntity.from(category)).toDomain();
    }

    @Override
    public Optional<Category> findById(long id) {
        return jpaRepository.findById(id).map(CategoryJpaEntity::toDomain);
    }

    @Override
    public List<Category> findAll() {
        return jpaRepository.findAll().stream()
                .map(CategoryJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByParentId(long parentId) {
        return jpaRepository.existsByParentId(parentId);
    }

    @Override
    public void deleteById(long id) {
        jpaRepository.deleteById(id);
    }
}
