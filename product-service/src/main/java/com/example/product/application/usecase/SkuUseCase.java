package com.example.product.application.usecase;

import com.example.product.domain.entity.ProductSku;

public interface SkuUseCase {

    record CreateCommand(String color, String size, int initialQuantity) {}

    ProductSku addSku(long productId, CreateCommand command);

    void updateStock(long skuId, int quantity);
}
