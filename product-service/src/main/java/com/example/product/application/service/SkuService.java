package com.example.product.application.service;

import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.SkuUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.common.exception.application.SkuNotFoundException;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkuService implements SkuUseCase {

    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional
    public ProductSku addSku(long productId, CreateCommand command) {
        ProductVersion currentVersion = productVersionRepository.findCurrentByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        ProductSku sku = ProductSku.of(currentVersion.getId(), command.color(), command.size(), productId);
        sku = productSkuRepository.save(sku);

        ProductStock stock = ProductStock.of(sku.getId(), command.initialQuantity());
        productStockRepository.save(stock);

        return sku;
    }

    @Override
    @Transactional
    public void updateStock(long skuId, int quantity) {
        ProductStock stock = productStockRepository.findBySkuId(skuId)
                .orElseThrow(() -> new SkuNotFoundException(skuId));

        stock.updateQuantity(quantity);
        productStockRepository.save(stock);
    }
}
