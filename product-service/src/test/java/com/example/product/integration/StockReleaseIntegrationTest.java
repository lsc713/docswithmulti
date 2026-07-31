package com.example.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * release 원자 조건부 전이(W2, D-P1-4·D-P1-5) 회귀 — 실 MySQL(Testcontainers) + 실 HTTP.
 * <p>RESERVED만 해제(RESERVED→RELEASED affected=1)일 때만 재고 복원. 이미 RELEASED/미존재는 no-op 200.
 * <p>동시 이중 release라도 DB가 전이를 직렬화 → 복원 1회(over-release 불가). Phase 3(cancel consumer)이
 * 동시 release를 실현하므로 지금 회귀로 고정.
 * <p>Boot 4.0.5 MockMvc는 동시성 실측 부적합(D-P1-6) → RANDOM_PORT + JDK HttpClient 실 HTTP. Docker 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Stock release 원자 조건부 전이 (RESERVED만·멱등 no-op·동시 이중 release)")
class StockReleaseIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product")
            .withUrlParam("useAffectedRows", "true");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("STOCK-04: reserve(available=6) → release → 200, available=10 복원, status=RELEASED")
    void releaseRestoresStockAndTransitionsStatus() throws Exception {
        long skuId = seedSku("REL-1", 10);
        assertThat(post("/v1/stock/reserve", reserveBody("pk-r1", skuId, 4)).statusCode()).isEqualTo(200);
        assertThat(availableQty(skuId)).isEqualTo(6);

        HttpResponse<String> rel = post("/v1/stock/release", releaseBody("pk-r1", skuId, 4));
        assertThat(rel.statusCode()).isEqualTo(200);
        assertThat(om.readValue(rel.body(), Map.class).get("released")).isEqualTo(true);
        assertThat(availableQty(skuId)).isEqualTo(10);
        assertThat(status("pk-r1", skuId)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("STOCK-04: 같은 release 재호출 → 200 no-op, available 불변(재복원 없음)")
    void releaseIsIdempotentNoOp() throws Exception {
        long skuId = seedSku("REL-2", 10);
        post("/v1/stock/reserve", reserveBody("pk-r2", skuId, 4));
        post("/v1/stock/release", releaseBody("pk-r2", skuId, 4)); // 1회 복원 → 10

        HttpResponse<String> again = post("/v1/stock/release", releaseBody("pk-r2", skuId, 4));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(availableQty(skuId)).isEqualTo(10); // 재복원 없음
    }

    @Test
    @DisplayName("STOCK-04: 예약 없는 paymentKey release → 200 no-op, available 불변")
    void releaseUnknownPaymentKeyIsNoOp() throws Exception {
        long skuId = seedSku("REL-3", 10);
        HttpResponse<String> rel = post("/v1/stock/release", releaseBody("pk-none", skuId, 4));
        assertThat(rel.statusCode()).isEqualTo(200);
        assertThat(availableQty(skuId)).isEqualTo(10);
    }

    @Test
    @DisplayName("W2 회귀: 동시 이중 release → 둘 다 200, available 정확히 10(4를 1회만 복원)")
    void concurrentDoubleReleaseRestoresOnce() throws Exception {
        long skuId = seedSku("REL-4", 10);
        post("/v1/stock/reserve", reserveBody("pk-r4", skuId, 4)); // available=6
        assertThat(availableQty(skuId)).isEqualTo(6);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (post("/v1/stock/release", releaseBody("pk-r4", skuId, 4)).statusCode() == 200) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ok.get()).isEqualTo(threads);          // 둘 다 200
        assertThat(availableQty(skuId)).isEqualTo(10);     // 4를 1회만 복원(14 아님 — over-release 불가)
        assertThat(status("pk-r4", skuId)).isEqualTo("RELEASED");
    }

    // --- helpers ---

    private long seedSku(String code, int stock) throws Exception {
        HttpResponse<String> seed = post("/v1/products", """
                {"name":"티셔츠","categoryId":%d,"skus":[{"skuCode":"%s","optionSummary":"opt","initialStock":%d}]}"""
                .formatted(CategoryFixtures.leafId(jdbc), code, stock));
        assertThat(seed.statusCode()).isEqualTo(200);
        Map<?, ?> body = om.readValue(seed.body(), Map.class);
        return ((Number) ((Map<?, ?>) ((List<?>) body.get("skus")).get(0)).get("skuId")).longValue();
    }

    private String reserveBody(String pk, long skuId, int qty) {
        return """
                {"paymentKey":"%s","items":[{"skuId":%d,"qty":%d}]}""".formatted(pk, skuId, qty);
    }

    private String releaseBody(String pk, long skuId, int qty) {
        return """
                {"paymentKey":"%s","items":[{"skuId":%d,"qty":%d}]}""".formatted(pk, skuId, qty);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private String status(String paymentKey, long skuId) {
        return jdbc.queryForObject(
                "SELECT status FROM stock_reservation WHERE payment_key = ? AND sku_id = ?",
                String.class, paymentKey, skuId);
    }
}
