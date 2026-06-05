package com.example.product.application.usecase;

import com.example.product.domain.entity.Category;

import java.util.List;

public interface CategoryUseCase {

    Category create(String name, Long parentId);

    List<Category> getAll();

    Category update(long id, String name);

    void delete(long id);
}
