package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.domain.entity.ProductStock;

public class ProductStockRepositoryImpl implements ProductStockRepository {

    private final ProductStockJpaRepository jpa;

    public ProductStockRepositoryImpl(ProductStockJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(ProductStock stock) {
        jpa.save(ProductStockJpaEntity.from(stock));
    }

    @Override
    public int tryReserve(long skuId, int qty) {
        return jpa.tryReserve(skuId, qty);
    }

    @Override
    public int restore(long skuId, int qty) {
        return jpa.restore(skuId, qty);
    }
}
