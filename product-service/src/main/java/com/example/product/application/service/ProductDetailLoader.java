package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.interfaces.ProductVariantRepository;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.Product;
import com.example.product.infrastructure.config.ReplicaRead;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.example.product.application.service.ProductQueryService.CategoryPathNode;
import static com.example.product.application.service.ProductQueryService.ImageRef;
import static com.example.product.application.service.ProductQueryService.ProductDetail;
import static com.example.product.application.service.ProductQueryService.SkuDetail;
import static com.example.product.application.service.ProductQueryService.VariantOption;

@Service
public class ProductDetailLoader {
    private final ProductQueryRepository queryRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;
    private final ProductVariantRepository variantRepository;

    public ProductDetailLoader(ProductQueryRepository queryRepository,
                               CategoryRepository categoryRepository,
                               ProductImageRepository imageRepository,
                               ProductVariantRepository variantRepository) {
        this.queryRepository = queryRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.variantRepository = variantRepository;
    }

    @ReplicaRead
    public ProductDetail load(Long productId) {
        Product product = queryRepository.findProductById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        List<CategoryPathNode> path = categoryPath(product.getCategoryId());

        Map<String, LinkedHashSet<String>> optionsByAttr = new LinkedHashMap<>();
        Map<String, Map<String, String>> variantBySku = new LinkedHashMap<>();
        for (ProductVariantRepository.VariantRow row : variantRepository.findVariantRows(productId)) {
            optionsByAttr.computeIfAbsent(row.getAttributeName(), key -> new LinkedHashSet<>()).add(row.getValue());
            variantBySku.computeIfAbsent(row.getSkuCode(), key -> new LinkedHashMap<>())
                    .put(row.getAttributeName(), row.getValue());
        }
        List<VariantOption> variantOptions = optionsByAttr.entrySet().stream()
                .map(entry -> new VariantOption(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        List<SkuDetail> skus = queryRepository.findSkuStock(productId).stream()
                .map(sku -> new SkuDetail(sku.skuId(), sku.skuCode(), sku.optionSummary(), sku.availableQty(),
                        sku.price(), variantBySku.getOrDefault(sku.skuCode(), Map.of())))
                .toList();

        List<ImageRef> images = imageRepository.findByProductId(productId).stream()
                .map(image -> new ImageRef(image.getId(), image.getS3Key()))
                .toList();

        Map<String, LinkedHashSet<String>> specsByAttr = new LinkedHashMap<>();
        for (ProductVariantRepository.DescriptiveRow row : variantRepository.findDescriptiveRows(productId)) {
            specsByAttr.computeIfAbsent(row.getAttributeName(), key -> new LinkedHashSet<>()).add(row.getValue());
        }
        List<VariantOption> specs = specsByAttr.entrySet().stream()
                .map(entry -> new VariantOption(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        return new ProductDetail(product.getId(), product.getName(), path, skus, images, variantOptions, specs);
    }

    private List<CategoryPathNode> categoryPath(Long leafId) {
        return categoryRepository.findPathByLeafId(leafId).stream()
                .map(category -> new CategoryPathNode(category.getLevel(), category.getId(), category.getName()))
                .toList();
    }
}
