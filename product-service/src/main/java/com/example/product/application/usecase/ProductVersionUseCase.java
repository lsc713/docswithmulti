package com.example.product.application.usecase;

import com.example.product.domain.entity.ProductVersion;

import java.math.BigDecimal;
import java.util.List;

public interface ProductVersionUseCase {

    record Command(String name, BigDecimal price, BigDecimal discountPrice, String attributes) {}

    ProductVersion createVersion(long productId, Command command);

    List<ProductVersion> getVersions(long productId);
}
