package com.example.product.application.usecase;

import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductVersion;

import java.math.BigDecimal;
import java.util.List;

public interface CreateProductUseCase {

    record SkuInput(String color, String size, int quantity) {}

    record Command(
            long merchantId,
            long categoryId,
            String name,
            BigDecimal price,
            BigDecimal discountPrice,
            String attributes,
            List<SkuInput> skus
    ) {}

    record Result(Product product, ProductVersion version, List<ProductSku> skus) {}

    Result execute(Command command);
}
