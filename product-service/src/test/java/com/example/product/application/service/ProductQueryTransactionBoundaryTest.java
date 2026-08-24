package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.domain.entity.Product;
import com.example.product.infrastructure.cache.ProductDetailCacheService;
import com.example.product.infrastructure.cache.ProductStockSnapshotCacheService;
import com.example.product.infrastructure.config.ReplicaReadAspect;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(ProductQueryTransactionBoundaryTest.Config.class)
class ProductQueryTransactionBoundaryTest {
    @Autowired ProductQueryService service;
    @Autowired ProductDetailCacheService detailCache;
    ProductStockSnapshotCacheService stockCache = Config.STOCK_CACHE;
    @Autowired ProductQueryRepository queryRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductImageRepository imageRepository;
    @Autowired ProductVariantRepository variantRepository;
    @Autowired RecordingTransactionManager transactionManager;
    @Autowired SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void clearTransactions() {
        transactionManager.readOnlyTransactions.clear();
        meterRegistry.clear();
    }

    @Test
    void detail_cache_hit_does_not_open_database_transaction() {
        ProductQueryService.ProductDetail cached = new ProductQueryService.ProductDetail(10L, "product", List.of(),
                List.of(new ProductQueryService.SkuDetail(101L, "SKU-1", "opt", 7, 1_000L, Map.of())),
                List.of(), List.of(), List.of());
        when(detailCache.getOrLoad(eq(10L), eq(ProductQueryService.ProductDetail.class), any())).thenReturn(cached);
        when(stockCache.getOrLoad(10L)).thenReturn(Map.of(101L, 0));

        assertThat(service.detail(10L).skus()).extracting(ProductQueryService.SkuDetail::availableQty)
                .containsExactly(0);
        assertThat(transactionManager.readOnlyTransactions).isEmpty();
    }

    @Test
    void detail_cache_miss_loads_database_in_one_read_only_transaction() {
        when(detailCache.getOrLoad(eq(20L), eq(ProductQueryService.ProductDetail.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        when(stockCache.getOrLoad(20L)).thenReturn(Map.of());
        when(queryRepository.findProductById(20L))
                .thenReturn(Optional.of(Product.reconstruct(20L, "loaded", 30L, null, null)));
        when(queryRepository.findSkuStock(20L)).thenReturn(List.of());
        when(categoryRepository.findPathByLeafId(30L)).thenReturn(List.of());
        when(imageRepository.findByProductId(20L)).thenReturn(List.of());
        when(variantRepository.findVariantRows(20L)).thenReturn(List.of());
        when(variantRepository.findDescriptiveRows(20L)).thenReturn(List.of());

        assertThat(service.detail(20L).name()).isEqualTo("loaded");
        assertThat(transactionManager.readOnlyTransactions).containsExactly(true);
        assertThat(meterRegistry.get("product.datasource.route")
                .tags("target", "replica", "outcome", "success")
                .counter().count()).isEqualTo(1);
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy
    static class Config {
        private static final ProductStockSnapshotCacheService STOCK_CACHE = mock(ProductStockSnapshotCacheService.class);

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ReplicaReadAspect replicaReadAspect(RecordingTransactionManager transactionManager,
                                            SimpleMeterRegistry meterRegistry) {
            return new ReplicaReadAspect(transactionManager, meterRegistry, true);
        }

        @Bean
        ProductQueryRepository queryRepository() {
            return mock(ProductQueryRepository.class);
        }

        @Bean
        CategoryRepository categoryRepository() {
            return mock(CategoryRepository.class);
        }

        @Bean
        ProductImageRepository imageRepository() {
            return mock(ProductImageRepository.class);
        }

        @Bean
        ProductVariantRepository variantRepository() {
            return mock(ProductVariantRepository.class);
        }

        @Bean
        ProductDetailCacheService detailCache() {
            return mock(ProductDetailCacheService.class);
        }

        @Bean
        ProductQueryService productQueryService(ProductQueryRepository queryRepository,
                                                CategoryRepository categoryRepository,
                                                ProductDetailCacheService detailCache,
                                                ProductDetailLoader detailLoader) {
            return new ProductQueryService(
                    queryRepository, categoryRepository, detailCache, STOCK_CACHE, detailLoader);
        }

        @Bean
        ProductDetailLoader productDetailLoader(ProductQueryRepository queryRepository,
                                                CategoryRepository categoryRepository,
                                                ProductImageRepository imageRepository,
                                                ProductVariantRepository variantRepository) {
            return new ProductDetailLoader(queryRepository, categoryRepository, imageRepository, variantRepository);
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<Boolean> readOnlyTransactions = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            readOnlyTransactions.add(definition.isReadOnly());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
