package com.example.product.application.interfaces;

import com.example.product.domain.entity.Product;

import java.util.List;
import java.util.Optional;

/**
 * 상품 저장소 인터페이스
 *
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(long id);

    List<Product> findAllByCategoryId(long categoryId);

    List<Product> findAllByMerchantId(long merchantId);

    List<Product> findAll();
}
