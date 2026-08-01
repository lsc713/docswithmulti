package com.example.product.integration;

import com.example.product.application.interfaces.ObjectStoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** presignUpload → PUT → exists → presignDownload → GET → delete → exists false 라운드트립(실 MinIO). */
@SpringBootTest
@Testcontainers
@DisplayName("S3ObjectStorageAdapter (MinIO round-trip)")
class S3ObjectStorageIT {

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio"))
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000);

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("app.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        r.add("app.s3.access-key", () -> "minioadmin");
        r.add("app.s3.secret-key", () -> "minioadmin");
        r.add("app.s3.bucket", () -> "product-images");
    }

    @Autowired ObjectStoragePort port;
    @Autowired S3Client s3Client;

    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void createBucket() {
        try {
            s3Client.createBucket(b -> b.bucket("product-images"));
        } catch (BucketAlreadyOwnedByYouException ignored) {
            // 이미 존재 — 멱등
        }
    }

    @Test
    void presign_put_then_exists_then_download() throws Exception {
        String key = "products/1/a.jpg";

        var up = port.presignUpload(key, "image/jpeg");
        HttpResponse<Void> put = http.send(
                HttpRequest.newBuilder(URI.create(up.uploadUrl()))
                        .header("Content-Type", "image/jpeg")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2, 3}))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        assertThat(port.exists(key)).isTrue();

        String getUrl = port.presignDownload(key);
        HttpResponse<byte[]> get = http.send(
                HttpRequest.newBuilder(URI.create(getUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).isEqualTo(200);

        port.delete(key);

        assertThat(port.exists(key)).isFalse();
    }
}
