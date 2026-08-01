package com.example.product.presentation.dto;

import com.example.product.application.interfaces.ProductQueryRepository;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** GET /v1/categories/{id}/products 응답 (BROWSE-01): 페이징된 상품 카드(최소가 + 썸네일) 목록. */
public record ProductListResponse(List<Item> content, int page, int size, long totalElements) {

    public record Item(Long id, String name, long minPrice, String thumbnailUrl) {}

    /** presign: thumbnailKey → presigned URL. */
    public static ProductListResponse from(Page<ProductQueryRepository.ProductCard> page,
                                           Function<String, String> presign) {
        List<Item> content = page.getContent().stream()
                .map(c -> new Item(c.id(), c.name(), c.minPrice(),
                        c.thumbnailKey() == null ? null : presign.apply(c.thumbnailKey())))
                .toList();
        return new ProductListResponse(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
