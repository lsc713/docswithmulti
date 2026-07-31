package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.common.exception.application.ProductCategoryInvalidException;
import com.example.product.domain.entity.Category;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 최소 등록(seed): product + SKU + 초기재고를 한 TX로 저장 (STOCK-02, PLINK-01). */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductStockRepository stockRepository;
    private final CategoryRepository categoryRepository;

    public CatalogService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          ProductStockRepository stockRepository,
                          CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.stockRepository = stockRepository;
        this.categoryRepository = categoryRepository;
    }

    public record SkuSeed(String skuCode, String optionSummary, int initialStock) {}

    public record SeededSku(Long skuId, String skuCode) {}

    public record SeedResult(Long productId, List<SeededSku> skus) {}

    @Transactional
    public SeedResult seed(String name, Long categoryId, List<SkuSeed> skus) {
        // PLINK-01: categoryId 는 존재하는 leaf(level 3)여야 한다. 부재/비-leaf 모두 400 PRODUCT_001.
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ProductCategoryInvalidException(categoryId));
        if (!category.isLeaf()) {
            throw new ProductCategoryInvalidException(categoryId);
        }
        Product product = productRepository.save(Product.create(name, categoryId));
        List<SeededSku> seeded = skus.stream().map(s -> {
            ProductSku sku = skuRepository.save(
                    ProductSku.create(product.getId(), s.skuCode(), s.optionSummary()));
            stockRepository.save(ProductStock.create(sku.getId(), s.initialStock()));
            return new SeededSku(sku.getId(), sku.getSkuCode());
        }).toList();
        return new SeedResult(product.getId(), seeded);
    }
}
