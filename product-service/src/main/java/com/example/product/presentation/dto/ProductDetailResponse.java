package com.example.product.presentation.dto;

import com.example.product.application.usecase.GetProductUseCase;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductVersion;

import java.math.BigDecimal;
import java.util.List;

public record ProductDetailResponse(
        Long id,
        long merchantId,
        long categoryId,
        String status,
        VersionInfo currentVersion,
        List<SkuInfo> skus
) {
    public record VersionInfo(
            Long id,
            String name,
            BigDecimal price,
            BigDecimal discountPrice,
            String attributes,
            int version
    ) {}

    public record SkuInfo(
            Long skuId,
            String color,
            String size,
            String skuCode,
            int quantity
    ) {}

    public static ProductDetailResponse from(Product product, ProductVersion version,
                                             List<GetProductUseCase.SkuWithStock> skus) {
        VersionInfo versionInfo = new VersionInfo(
                version.getId(),
                version.getName(),
                version.getPrice(),
                version.getDiscountPrice(),
                version.getAttributes(),
                version.getVersion()
        );

        List<SkuInfo> skuInfos = skus.stream()
                .map(s -> new SkuInfo(
                        s.sku().getId(),
                        s.sku().getColor(),
                        s.sku().getSize(),
                        s.sku().getSkuCode(),
                        s.stock().getQuantity()
                ))
                .toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getMerchantId(),
                product.getCategoryId(),
                product.getStatus().name(),
                versionInfo,
                skuInfos
        );
    }
}
