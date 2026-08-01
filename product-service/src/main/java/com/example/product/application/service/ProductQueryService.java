package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.Category;
import com.example.product.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 카테고리 기반 상품 브라우징 읽기 전용 서비스 (BROWSE-01/02). */
@Service
public class ProductQueryService {

    private final ProductQueryRepository queryRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;

    public ProductQueryService(ProductQueryRepository queryRepository,
                               CategoryRepository categoryRepository,
                               ProductImageRepository imageRepository) {
        this.queryRepository = queryRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
    }

    public record CategoryPathNode(int level, Long id, String name) {}

    public record SkuDetail(String skuCode, String optionSummary, int availableQty, long price) {}

    /** id + 원본 S3 key. presign 은 컨트롤러 책임, id는 delete/reorder 배선용. */
    public record ImageRef(Long id, String s3Key) {}

    /** images: sort_order asc. */
    public record ProductDetail(Long id, String name,
                                List<CategoryPathNode> category, List<SkuDetail> skus,
                                List<ImageRef> images) {}

    /** BROWSE-01: 카테고리 스코프 상품 목록. category 부재 → 404, valid-but-empty → 빈 페이지. */
    @Transactional(readOnly = true)
    public Page<Product> listByCategory(Long categoryId, int page, int size) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        List<Long> ids = queryRepository.descendantCategoryIds(categoryId);
        return queryRepository.findByCategoryIds(ids, page, size);
    }

    /** BROWSE-01: 카테고리 스코프 상품 카드(최소가 + 썸네일) 목록. listByCategory 와 동일한 검증 흐름. */
    @Transactional(readOnly = true)
    public Page<ProductQueryRepository.ProductCard> listCards(Long categoryId, int page, int size) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        List<Long> ids = queryRepository.descendantCategoryIds(categoryId);
        return queryRepository.findCardsByCategoryIds(ids, page, size);
    }

    /** BROWSE-02: 상품 상세 = 대/중/소 경로 + SKU(코드/옵션/availableQty/price). 부재 → 404. */
    @Transactional(readOnly = true)
    public ProductDetail detail(Long productId) {
        Product product = queryRepository.findProductById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        List<CategoryPathNode> path = categoryPath(product.getCategoryId());

        List<SkuDetail> skus = queryRepository.findSkuStock(productId).stream()
                .map(s -> new SkuDetail(s.skuCode(), s.optionSummary(), s.availableQty(), s.price()))
                .toList();

        List<ImageRef> images = imageRepository.findByProductId(productId).stream()
                .map(img -> new ImageRef(img.getId(), img.getS3Key()))
                .toList();

        return new ProductDetail(product.getId(), product.getName(), path, skus, images);
    }

    /** leaf 에서 parent 로 올라가며 조상 수집 후 root→leaf 로 뒤집는다 (정상 트리는 3노드). */
    private List<CategoryPathNode> categoryPath(Long leafId) {
        List<CategoryPathNode> reversed = new ArrayList<>();
        Long cursor = leafId;
        while (cursor != null) {
            Category c = categoryRepository.findById(cursor).orElse(null);
            if (c == null) break;
            reversed.add(new CategoryPathNode(c.getLevel(), c.getId(), c.getName()));
            cursor = c.getParentId();
        }
        List<CategoryPathNode> path = new ArrayList<>(reversed);
        java.util.Collections.reverse(path);
        return path;
    }
}
