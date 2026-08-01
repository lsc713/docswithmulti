package com.example.product.presentation.controller;

import com.example.product.application.service.ProductQueryService;
import com.example.product.presentation.dto.ProductDetailResponse;
import com.example.product.presentation.dto.ProductListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 읽기 전용 브라우징 엔드포인트 (BROWSE-01/02). 쓰기는 ProductController(POST). */
@RestController
public class ProductQueryController {

    private final ProductQueryService queryService;

    public ProductQueryController(ProductQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/v1/products/{id}")
    public ProductDetailResponse detail(@PathVariable Long id) {
        return ProductDetailResponse.from(queryService.detail(id));
    }

    // presign: 이 태스크에서는 key -> null 임시 배선. Task 8 이 ObjectStoragePort::presignDownload 로 교체.
    @GetMapping("/v1/categories/{id}/products")
    public ProductListResponse listByCategory(@PathVariable Long id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ProductListResponse.from(queryService.listCards(id, page, size), key -> null);
    }
}
