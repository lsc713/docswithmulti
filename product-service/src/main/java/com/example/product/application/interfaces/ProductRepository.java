package com.example.product.application.interfaces;

import com.example.product.domain.entity.Product;

public interface ProductRepository {
    /** INSERT 후 생성된 id가 채워진 Product 반환. */
    Product save(Product product);
}
