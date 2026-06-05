package com.example.product.presentation.controller;

import com.example.product.application.usecase.CategoryUseCase;
import com.example.product.domain.entity.Category;
import com.example.product.presentation.dto.CategoryResponse;
import com.example.product.presentation.dto.CreateCategoryRequest;
import com.example.product.presentation.dto.UpdateCategoryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryUseCase categoryUseCase;

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        Category category = categoryUseCase.create(request.name(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(category));
    }

    @GetMapping
    public ResponseEntity<List<CategoryResponse>> getAll() {
        List<CategoryResponse> responses = categoryUseCase.getAll().stream()
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(@PathVariable long id,
                                                   @Valid @RequestBody UpdateCategoryRequest request) {
        Category category = categoryUseCase.update(id, request.name());
        return ResponseEntity.ok(CategoryResponse.from(category));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        categoryUseCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
