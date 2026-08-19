package com.example.product.infrastructure.cache;

import org.springframework.stereotype.Component;

@Component
public class ProductDetailCacheInvalidation {
    private final ProductDetailCacheService cacheService;

    public ProductDetailCacheInvalidation(ProductDetailCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public void onProductChanged(Long productId) {
        cacheService.evict(productId);
    }

    public void onPriceChanged(Long productId) {
        cacheService.evict(productId);
    }

    public void onStockChanged(Long productId) {
        cacheService.evict(productId);
    }
}
