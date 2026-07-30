package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 최소 등록(seed): product + SKU + 초기재고를 한 TX로 저장 (STOCK-02). */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductStockRepository stockRepository;

    public CatalogService(ProductRepository productRepository,
                          ProductSkuRepository skuRepository,
                          ProductStockRepository stockRepository) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.stockRepository = stockRepository;
    }

    public record SkuSeed(String skuCode, String optionSummary, int initialStock) {}

    public record SeededSku(Long skuId, String skuCode) {}

    public record SeedResult(Long productId, List<SeededSku> skus) {}

    @Transactional
    public SeedResult seed(String name, List<SkuSeed> skus) {
        Product product = productRepository.save(Product.create(name));
        List<SeededSku> seeded = skus.stream().map(s -> {
            ProductSku sku = skuRepository.save(
                    ProductSku.create(product.getId(), s.skuCode(), s.optionSummary()));
            stockRepository.save(ProductStock.create(sku.getId(), s.initialStock()));
            return new SeededSku(sku.getId(), sku.getSkuCode());
        }).toList();
        return new SeedResult(product.getId(), seeded);
    }
}
