package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.infrastructure.cache.ProductDetailCacheService;
import com.example.product.infrastructure.cache.ProductStockSnapshotCacheService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 카테고리 기반 상품 브라우징 읽기 전용 서비스 (BROWSE-01/02). */
@Service
public class ProductQueryService {

    private final ProductQueryRepository queryRepository;
    private final CategoryRepository categoryRepository;
    private final ProductDetailCacheService cacheService;
    private final ProductStockSnapshotCacheService stockSnapshotCacheService;
    private final ProductDetailLoader detailLoader;

    public ProductQueryService(ProductQueryRepository queryRepository,
                               CategoryRepository categoryRepository,
                               ProductDetailCacheService cacheService,
                               ProductStockSnapshotCacheService stockSnapshotCacheService,
                               ProductDetailLoader detailLoader) {
        this.queryRepository = queryRepository;
        this.categoryRepository = categoryRepository;
        this.cacheService = cacheService;
        this.stockSnapshotCacheService = stockSnapshotCacheService;
        this.detailLoader = detailLoader;
    }

    public record CategoryPathNode(int level, Long id, String name) {}

    /** variant: 변형 속성명 → 값 (선언 순서 유지). 변형 없는 SKU 는 빈 맵. */
    public record SkuDetail(Long skuId, String skuCode, String optionSummary, int availableQty, long price,
                            Map<String, String> variant) {}

    /** 변형 속성별 값 집합 (attribute 선언 순서, value 등장 순서). */
    public record VariantOption(String attribute, List<String> values) {}

    /** id + 원본 S3 key. presign 은 컨트롤러 책임, id는 delete/reorder 배선용. */
    public record ImageRef(Long id, String s3Key) {}

    /** images: sort_order asc. specs: 서술 속성별 값 배열(다값), 변형과 병존. */
    public record ProductDetail(Long id, String name,
                                List<CategoryPathNode> category, List<SkuDetail> skus,
                                List<ImageRef> images, List<VariantOption> variantOptions,
                                List<VariantOption> specs) {}

    /** BROWSE-01: 카테고리 스코프 상품 카드(최소가 + 썸네일) 목록. category 부재 → 404, valid-but-empty → 빈 페이지. */
    @Transactional(readOnly = true)
    public Page<ProductQueryRepository.ProductCard> listCards(Long categoryId, int page, int size) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        List<Long> ids = queryRepository.descendantCategoryIds(categoryId);
        return queryRepository.findCardsByCategoryIds(ids, page, size);
    }

    /** BROWSE-02: 상품 상세 = 대/중/소 경로 + SKU(코드/옵션/availableQty/price). 부재 → 404. */
    public ProductDetail detail(Long productId) {
        ProductDetail detail = cacheService.getOrLoad(productId, ProductDetail.class, () -> detailLoader.load(productId));
        return withAvailability(detail, stockSnapshotCacheService.getOrLoad(productId));
    }

    private static ProductDetail withAvailability(ProductDetail detail, Map<Long, Integer> availability) {
        List<SkuDetail> skus = detail.skus().stream()
                .map(sku -> new SkuDetail(sku.skuId(), sku.skuCode(), sku.optionSummary(),
                        availability.get(sku.skuId()), sku.price(), sku.variant()))
                .toList();
        return new ProductDetail(detail.id(), detail.name(), detail.category(), skus, detail.images(),
                detail.variantOptions(), detail.specs());
    }
}
