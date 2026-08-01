package com.example.product.application.service;

import com.example.product.application.interfaces.ObjectStoragePort;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.common.exception.application.InvalidImageKeyException;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.ProductImage;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 상품 이미지 presign(업로드 URL 발급) + confirm(업로드 완료 기록). 둘 다 ADMIN 전용(컨트롤러 가드). */
@Service
public class ProductImageService {

    private final ProductQueryRepository productQueryRepository;
    private final ProductImageRepository imageRepository;
    private final ObjectStoragePort objectStoragePort;

    public ProductImageService(ProductQueryRepository productQueryRepository,
                               ProductImageRepository imageRepository,
                               ObjectStoragePort objectStoragePort) {
        this.productQueryRepository = productQueryRepository;
        this.imageRepository = imageRepository;
        this.objectStoragePort = objectStoragePort;
    }

    public record Presigned(String key, String uploadUrl) {}

    public Presigned presign(Long productId, String contentType) {
        productQueryRepository.findProductById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        String key = "products/" + productId + "/" + UUID.randomUUID();
        String uploadUrl = objectStoragePort.presignUpload(key, contentType).uploadUrl();
        return new Presigned(key, uploadUrl);
    }

    public Long confirm(Long productId, String key, Integer sortOrder) {
        productQueryRepository.findProductById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (!objectStoragePort.exists(key)) {
            throw new InvalidImageKeyException(key);
        }
        int order = sortOrder != null ? sortOrder : imageRepository.nextSortOrder(productId);
        return imageRepository.save(ProductImage.create(productId, key, order)).getId();
    }
}
