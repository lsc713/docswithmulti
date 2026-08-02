package com.example.product.application.service;

import com.example.product.application.interfaces.ObjectStoragePort;
import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.common.exception.application.ImageNotFoundException;
import com.example.product.common.exception.application.InvalidImageKeyException;
import com.example.product.common.exception.application.ProductNotFoundException;
import com.example.product.domain.entity.ProductImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 상품 이미지 presign/confirm/delete/reorder. 전부 ADMIN 전용(컨트롤러 가드). */
@Slf4j
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

    public void delete(Long productId, Long imageId) {
        var img = imageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ImageNotFoundException(imageId));
        imageRepository.deleteByIdAndProductId(imageId, productId);
        try {
            objectStoragePort.delete(img.getS3Key());
        } catch (RuntimeException e) {
            log.warn("S3 delete 실패(고아 허용) key={}", img.getS3Key(), e);
        }
        // ponytail: 고아 객체 스캐너는 필요해지면
    }

    public void reorder(Long productId, List<Long> imageIds) {
        imageRepository.updateOrder(productId, imageIds);
    }
}
