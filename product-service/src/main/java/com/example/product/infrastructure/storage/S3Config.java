package com.example.product.infrastructure.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * MinIO(S3 호환) 연결 설정 + app.s3 프로퍼티 홀더.
 * S3Client/S3Presigner 빈만 노출 — 실제 사용처(presigned PUT/GET)는 Task 4.
 *
 * 빌더 제네릭(S3BaseClientBuilder) 공유 헬퍼는 AWS SDK v2 타입 파라미터가 까다로워
 * 각 @Bean 메서드에 4줄을 그대로 중복 — 제네릭 헬퍼보다 명확하고 컴파일이 안정적이다.
 */
@Configuration
@ConfigurationProperties(prefix = "app.s3")
@Getter
@Setter
public class S3Config {

    private String endpoint;
    private String region;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private int presignTtlSeconds;
    private boolean pathStyle;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(creds())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(creds())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();
    }

    private StaticCredentialsProvider creds() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
