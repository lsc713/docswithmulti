package com.example.product.presentation.controller;

import com.example.product.application.interfaces.ObjectStoragePort;
import com.example.product.application.service.ProductQueryService;
import com.example.product.presentation.dto.ProductDetailResponse;
import com.example.product.presentation.dto.ProductListResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 읽기 전용 브라우징 엔드포인트 (BROWSE-01/02). 쓰기는 ProductController(POST). */
@RestController
public class ProductQueryController {

    private final ProductQueryService queryService;
    private final ObjectStoragePort objectStoragePort;
    private final Timer detailResponseAssemblyTimer;

    public ProductQueryController(ProductQueryService queryService, ObjectStoragePort objectStoragePort,
                                  MeterRegistry meterRegistry) {
        this.queryService = queryService;
        this.objectStoragePort = objectStoragePort;
        this.detailResponseAssemblyTimer = Timer.builder("product.detail.response.assembly").register(meterRegistry);
    }

    @GetMapping("/v1/products/{id}")
    public ProductDetailResponse detail(@PathVariable Long id) {
        return detailResponseAssemblyTimer.record(
                () -> ProductDetailResponse.from(queryService.detail(id), objectStoragePort::presignDownload));
    }

    @GetMapping("/v1/categories/{id}/products")
    public ProductListResponse listByCategory(@PathVariable Long id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return ProductListResponse.from(queryService.listCards(id, page, size), objectStoragePort::presignDownload);
    }
}
