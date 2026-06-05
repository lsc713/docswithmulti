package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.CreateProductUseCase;
import com.example.product.domain.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CreateProductService implements CreateProductUseCase {

    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public Result execute(Command command) {
        // 1. Save product
        Product product = Product.of(command.merchantId(), command.categoryId());
        product = productRepository.save(product);

        // 2. Save first version
        ProductVersion version = ProductVersion.createFirst(
                product.getId(),
                command.name(),
                command.price(),
                command.discountPrice(),
                command.attributes()
        );
        version = productVersionRepository.save(version);

        // 3. Save SKUs and stocks
        List<ProductSku> savedSkus = new ArrayList<>();
        for (CreateProductUseCase.SkuInput skuInput : command.skus()) {
            ProductSku sku = ProductSku.of(version.getId(), skuInput.color(), skuInput.size(), product.getId());
            sku = productSkuRepository.save(sku);

            ProductStock stock = ProductStock.of(sku.getId(), skuInput.quantity());
            productStockRepository.save(stock);

            savedSkus.add(sku);
        }

        return new Result(product, version, savedSkus);
    }
}
