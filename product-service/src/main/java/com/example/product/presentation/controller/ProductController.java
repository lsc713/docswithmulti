package com.example.product.presentation.controller;

import com.example.product.application.service.CatalogService;
import com.example.product.application.service.CatalogService.ProductAttributeSeed;
import com.example.product.application.service.CatalogService.SkuSeed;
import com.example.product.presentation.dto.SeedRequest;
import com.example.product.presentation.dto.SeedResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/products")
public class ProductController {

    private final CatalogService catalogService;

    public ProductController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping
    public SeedResponse seed(@Valid @RequestBody SeedRequest req) {
        List<ProductAttributeSeed> attributes = req.attributesOrEmpty().stream()
                .map(a -> new ProductAttributeSeed(a.attributeId(), a.isVariant()))
                .toList();
        List<SkuSeed> skus = req.skus().stream()
                .map(s -> new SkuSeed(s.skuCode(), s.optionSummary(), s.initialStock(), s.price(),
                        s.variantValueIds() == null ? List.of() : s.variantValueIds()))
                .toList();
        return SeedResponse.from(catalogService.seed(req.name(), req.categoryId(), attributes, skus,
                req.descriptiveValueIdsOrEmpty()));
    }
}
