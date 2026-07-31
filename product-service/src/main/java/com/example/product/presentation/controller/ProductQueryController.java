package com.example.product.presentation.controller;

import com.example.product.application.service.ProductQueryService;
import com.example.product.presentation.dto.ProductDetailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
