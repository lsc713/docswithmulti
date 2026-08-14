package com.example.product.presentation.controller;

import com.example.product.application.service.CategoryService;
import com.example.product.common.exception.application.ForbiddenException;
import com.example.product.presentation.dto.CategoryResponse;
import com.example.product.presentation.dto.CreateCategoryRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public CategoryResponse create(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @Valid @RequestBody CreateCategoryRequest req) {
        if (!"ADMIN".equals(role)) throw new ForbiddenException();
        return CategoryResponse.from(categoryService.create(req.parentId(), req.name()));
    }

    @GetMapping
    public List<CategoryResponse.Node> tree() {
        return categoryService.getTree().stream().map(CategoryResponse.Node::from).toList();
    }
}
