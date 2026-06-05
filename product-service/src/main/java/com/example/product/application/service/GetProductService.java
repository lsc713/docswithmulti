package com.example.product.application.service;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.application.interfaces.ProductSkuRepository;
import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.application.interfaces.ProductVersionRepository;
import com.example.product.application.usecase.GetProductUseCase;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.Product;
import com.example.product.domain.entity.ProductSku;
import com.example.product.domain.entity.ProductStock;
import com.example.product.domain.entity.ProductVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetProductService implements GetProductUseCase {

    private final ProductRepository productRepository;
    private final ProductVersionRepository productVersionRepository;
    private final ProductSkuRepository productSkuRepository;
    private final ProductStockRepository productStockRepository;

    @Override
    @Transactional(readOnly = true)
    public DetailResult getDetail(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        ProductVersion currentVersion = productVersionRepository.findCurrentByProductId(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        List<ProductSku> skus = productSkuRepository.findAllByProductVersionId(currentVersion.getId());

        List<SkuWithStock> skuWithStocks = new ArrayList<>();
        for (ProductSku sku : skus) {
            ProductStock stock = productStockRepository.findBySkuId(sku.getId())
                    .orElse(ProductStock.of(sku.getId(), 0));
            skuWithStocks.add(new SkuWithStock(sku, stock));
        }

        return new DetailResult(product, currentVersion, skuWithStocks);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> list(Long categoryId, Long merchantId) {
        if (categoryId != null) {
            return productRepository.findAllByCategoryId(categoryId);
        }
        if (merchantId != null) {
            return productRepository.findAllByMerchantId(merchantId);
        }
        return productRepository.findAll();
    }
}
