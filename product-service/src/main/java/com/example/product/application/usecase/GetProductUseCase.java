package com.example.product.application.usecase;

import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import com.example.product.domain.entity.ProductVersion;

import java.util.List;

public interface GetProductUseCase {

    record SkuWithStock(ProductSku sku, ProductStock stock) {}

    record DetailResult(Product product, ProductVersion currentVersion, List<SkuWithStock> skus) {}

    DetailResult getDetail(long productId);

    List<Product> list(Long categoryId, Long merchantId);
}
