package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductStockRepository;
import com.example.product.domain.entity.ProductStock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class ProductStockRepositoryImpl implements ProductStockRepository {

    private final ProductStockJpaRepository jpa;
    private final JdbcTemplate jdbc;

    public ProductStockRepositoryImpl(ProductStockJpaRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public void save(ProductStock stock) {
        jpa.save(ProductStockJpaEntity.from(stock));
    }

    @Override
    public int[] tryReserveAll(List<Adjustment> adjustments) {
        return jdbc.batchUpdate("""
            UPDATE product_stock
               SET available_qty = available_qty - ?, updated_at = CURRENT_TIMESTAMP(6)
             WHERE sku_id = ? AND available_qty >= ?
            """, adjustments.stream()
                .map(a -> new Object[]{a.qty(), a.skuId(), a.qty()})
                .toList());
    }

    @Override
    public int[] restoreAll(List<Adjustment> adjustments) {
        return jdbc.batchUpdate("""
            UPDATE product_stock
               SET available_qty = available_qty + ?, updated_at = CURRENT_TIMESTAMP(6)
             WHERE sku_id = ?
            """, adjustments.stream()
                .map(a -> new Object[]{a.qty(), a.skuId()})
                .toList());
    }
}
