package com.example.product.application.interfaces;

import com.example.product.domain.entity.ProductImage;

import java.util.List;
import java.util.Optional;

public interface ProductImageRepository {
    /** INSERT 후 생성된 id가 채워진 ProductImage 반환. */
    ProductImage save(ProductImage img);

    /** sort_order asc, id asc. */
    List<ProductImage> findByProductId(Long productId);

    /** max(sort_order)+1, 없으면 0. */
    int nextSortOrder(Long productId);

    Optional<ProductImage> findByIdAndProductId(Long id, Long productId);

    void deleteByIdAndProductId(Long id, Long productId);

    /** 리스트 인덱스를 sort_order 로 반영. 해당 productId 소속 행만 대상. */
    void updateOrder(Long productId, List<Long> imageIdsInOrder);
}
