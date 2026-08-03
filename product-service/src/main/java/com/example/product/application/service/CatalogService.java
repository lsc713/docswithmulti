package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.common.exception.application.ProductCategoryInvalidException;
import com.example.product.domain.entity.Category;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductAttribute;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 최소 등록(seed): product + 변형 속성 선언 + SKU + 초기재고를 한 TX로 저장 (STOCK-02, PLINK-01, VAR-01). */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductStockRepository stockRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository variantRepository;

    public CatalogService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          ProductStockRepository stockRepository,
                          CategoryRepository categoryRepository,
                          ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.stockRepository = stockRepository;
        this.categoryRepository = categoryRepository;
        this.variantRepository = variantRepository;
    }

    /** 상품이 선언하는 속성 + 역할(변형/서술). */
    public record ProductAttributeSeed(Long attributeId, boolean isVariant) {}

    public record SkuSeed(String skuCode, String optionSummary, int initialStock, long price,
                          List<Long> variantValueIds) {
        /** 하위호환: 변형 없는 기존 등록 경로. */
        public SkuSeed(String skuCode, String optionSummary, int initialStock, long price) {
            this(skuCode, optionSummary, initialStock, price, List.of());
        }
    }

    public record SeededSku(Long skuId, String skuCode) {}

    public record SeedResult(Long productId, List<SeededSku> skus) {}

    /** 하위호환 오버로드: 변형 선언 없는 기존 등록. */
    @Transactional
    public SeedResult seed(String name, Long categoryId, List<SkuSeed> skus) {
        return seed(name, categoryId, List.of(), skus);
    }

    @Transactional
    public SeedResult seed(String name, Long categoryId, List<ProductAttributeSeed> attributes, List<SkuSeed> skus) {
        // PLINK-01: categoryId 는 존재하는 leaf(level 3)여야 한다. 부재/비-leaf 모두 400 PRODUCT_001.
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ProductCategoryInvalidException(categoryId));
        if (!category.isLeaf()) {
            throw new ProductCategoryInvalidException(categoryId);
        }
        Product product = productRepository.save(Product.create(name, categoryId));

        // 변형/서술 속성 선언 저장 (Task 3 에서 저장 전 검증 추가; tracer 는 배선만).
        if (!attributes.isEmpty()) {
            variantRepository.saveProductAttributes(product.getId(), attributes.stream()
                    .map(a -> ProductAttribute.create(product.getId(), a.attributeId(), a.isVariant()))
                    .toList());
        }

        List<SeededSku> seeded = skus.stream().map(s -> {
            ProductSku sku = skuRepository.save(
                    ProductSku.create(product.getId(), s.skuCode(), s.optionSummary(), s.price()));
            stockRepository.save(ProductStock.create(sku.getId(), s.initialStock())); // INV-01: 재고 저장 무변경
            if (!s.variantValueIds().isEmpty()) {
                variantRepository.saveSkuVariantValues(sku.getId(), s.variantValueIds());
            }
            return new SeededSku(sku.getId(), sku.getSkuCode());
        }).toList();
        return new SeedResult(product.getId(), seeded);
    }
}
