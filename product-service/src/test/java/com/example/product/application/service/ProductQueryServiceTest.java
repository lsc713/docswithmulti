package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.infrastructure.cache.ProductDetailCacheService;
import com.example.product.infrastructure.cache.ProductStockSnapshotCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductQueryServiceTest {

    @Test
    void detail_replaces_cached_sku_quantity_with_stock_snapshot() {
        ProductDetailCacheService detailCache = mock(ProductDetailCacheService.class);
        ProductStockSnapshotCacheService stockSnapshot = mock(ProductStockSnapshotCacheService.class);
        ProductQueryService.ProductDetail cached = new ProductQueryService.ProductDetail(10L, "product", List.of(),
                List.of(new ProductQueryService.SkuDetail(101L, "SKU-1", "opt", 7, 1_000L, Map.of())),
                List.of(), List.of(), List.of());
        when(detailCache.getOrLoad(eq(10L), eq(ProductQueryService.ProductDetail.class), any())).thenReturn(cached);
        when(stockSnapshot.getOrLoad(10L)).thenReturn(Map.of(101L, 0));

        var service = new ProductQueryService(mock(ProductQueryRepository.class), mock(CategoryRepository.class),
                mock(ProductImageRepository.class), mock(ProductVariantRepository.class), detailCache, stockSnapshot);

        assertThat(service.detail(10L).skus()).extracting(ProductQueryService.SkuDetail::availableQty)
                .containsExactly(0);
    }
}
