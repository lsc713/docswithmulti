package com.example.product.infrastructure.persistence;

import com.example.product.application.interfaces.ProductRepository;
import com.example.product.domain.entity.Product;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    @Override
    public Product save(Product product) {
        return jpaRepository.save(ProductJpaEntity.from(product)).toDomain();
    }

    @Override
    public Optional<Product> findById(long id) {
        return jpaRepository.findById(id).map(ProductJpaEntity::toDomain);
    }

    @Override
    public List<Product> findAllByCategoryId(long categoryId) {
        return jpaRepository.findAllByCategoryId(categoryId).stream()
                .map(ProductJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<Product> findAllByMerchantId(long merchantId) {
        return jpaRepository.findAllByMerchantId(merchantId).stream()
                .map(ProductJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<Product> findAll() {
        return jpaRepository.findAll().stream()
                .map(ProductJpaEntity::toDomain)
                .toList();
    }
}
