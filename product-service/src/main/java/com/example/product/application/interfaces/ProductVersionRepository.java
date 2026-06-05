package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductVersion;

import java.util.List;
import java.util.Optional;

/**
 * 상품 버전 저장소 인터페이스
 *
 * infrastructure 레이어에서 JPA로 구현한다.
 */
public interface ProductVersionRepository {

    ProductVersion save(ProductVersion productVersion);

    Optional<ProductVersion> findCurrentByProductId(long productId);

    List<ProductVersion> findAllByProductId(long productId);
}
