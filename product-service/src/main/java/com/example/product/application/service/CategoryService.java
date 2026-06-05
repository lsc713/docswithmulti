package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.usecase.CategoryUseCase;
import com.example.product.common.exception.application.CategoryHasChildrenException;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.domain.entity.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService implements CategoryUseCase {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public Category create(String name, Long parentId) {
        if (parentId == null) {
            Category root = Category.createRoot(name);
            return categoryRepository.save(root);
        }

        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new CategoryNotFoundException(parentId));

        Category child = Category.createChild(name, parentId, parent.getDepth());
        return categoryRepository.save(child);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> getAll() {
        return categoryRepository.findAll();
    }

    @Override
    @Transactional
    public Category update(long id, String name) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        category.updateName(name);
        return categoryRepository.save(category);
    }

    @Override
    @Transactional
    public void delete(long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        if (categoryRepository.existsByParentId(id)) {
            throw new CategoryHasChildrenException(id);
        }

        categoryRepository.deleteById(id);
    }
}
