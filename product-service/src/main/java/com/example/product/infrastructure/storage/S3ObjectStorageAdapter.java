package com.example.product.infrastructure.storage;

import com.example.product.application.interfaces.ObjectStoragePort;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

/** ObjectStoragePort의 실 구현 — S3Client(HEAD/DELETE) + S3Presigner(PUT/GET presign). */
@Component
public class S3ObjectStorageAdapter implements ObjectStoragePort {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public S3ObjectStorageAdapter(S3Client s3, S3Presigner presigner, S3Config s3Config) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = s3Config.getBucket();
        this.ttl = Duration.ofSeconds(s3Config.getPresignTtlSeconds());
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType) {
        var presigned = presigner.presignPutObject(b -> b
                .signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key).contentType(contentType)));
        return new PresignedUpload(presigned.url().toString());
    }

    @Override
    public String presignDownload(String key) {
        var presigned = presigner.presignGetObject(b -> b
                .signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(bucket).key(key)));
        return presigned.url().toString();
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
