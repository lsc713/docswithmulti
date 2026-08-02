package com.example.product.application.interfaces;

/**
 * 상품 이미지 오브젝트 스토리지(S3/MinIO) 포트.
 * 도메인/애플리케이션 계층은 AWS SDK 타입을 직접 참조하지 않는다 — infrastructure.storage 어댑터만 구현.
 */
public interface ObjectStoragePort {

    record PresignedUpload(String uploadUrl) {}

    PresignedUpload presignUpload(String key, String contentType);

    String presignDownload(String key);

    boolean exists(String key);

    void delete(String key);
}
