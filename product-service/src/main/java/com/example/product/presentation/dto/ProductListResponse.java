package com.example.product.presentation.dto;

import com.example.product.domain.entity.Product;
import org.springframework.data.domain.Page;

import java.util.List;

/** GET /v1/categories/{id}/products 응답 (BROWSE-01): 페이징된 상품 목록. */
public record ProductListResponse(List<Item> content, int page, int size, long totalElements) {

    public record Item(Long id, String name) {}

    public static ProductListResponse from(Page<Product> page) {
        List<Item> content = page.getContent().stream()
                .map(p -> new Item(p.getId(), p.getName()))
                .toList();
        return new ProductListResponse(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
