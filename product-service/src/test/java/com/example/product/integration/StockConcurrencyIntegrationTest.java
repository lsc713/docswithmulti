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
 * 동시 reserve 오버셀 부재(STOCK-03, D-P1-3)의 결정적 증거 — 실 MySQL(Testcontainers) + 실 HTTP 동시 부하.
 * <p>Boot 4.0.5 MockMvc 동시성 부적합(D-P1-6) → RANDOM_PORT + JDK HttpClient + ExecutorService.
 * <p>시나리오 1(서로 다른 키): 재고 경합 — 원자 조건부 UPDATE로 정확히 1건만 성공, available 음수 불가.
 * <p>시나리오 2(같은 키 burst): 멱등 게이트(W1) — INSERT-ON-DUPLICATE로 동시에도 500/uk위반 없이 1회만 차감.
 * Docker 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("동시 reserve 오버셀 부재 + 같은 키 멱등 burst")
class StockConcurrencyIntegrationTest {

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
    @DisplayName("STOCK-03: 재고1, 서로 다른 키 20건 동시 → 200 정확히 1건·409 19건·available=0(오버셀 없음)")
    void concurrentDistinctKeyReserveNeverOversells() throws Exception {
        int n = 20;
        long skuId = seedSku("CONC-DISTINCT", 1);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        fire(n, i -> reserveBody("pk-" + i, skuId, 1), (body, code) -> {
            if (code == 200) {
                ok.incrementAndGet();
            } else if (code == 409) {
                conflict.incrementAndGet();
                assertThat(readCodeUnchecked(body)).isEqualTo("STOCK_001");
            }
        });

        assertThat(ok.get()).isEqualTo(1);              // 정확히 1건만 재고 획득
        assertThat(conflict.get()).isEqualTo(n - 1);    // 나머지 전부 409
        assertThat(availableQty(skuId)).isEqualTo(0);   // 음수 불가 — 오버셀 없음
        assertThat(reservedRowCount(skuId)).isEqualTo(1);
    }

    @Test
    @DisplayName("STOCK-04(W1): 재고10, 같은 키 20건 동시 → 200 20건·available=9(1회만 차감)·예약행 1개(uk위반 500 없음)")
    void concurrentSameKeyBurstIsIdempotent() throws Exception {
        int n = 20;
        long skuId = seedSku("CONC-SAME", 10);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        fire(n, i -> reserveBody("pk-same", skuId, 1), (body, code) -> {
            if (code == 200) ok.incrementAndGet();
            else other.incrementAndGet();
        });

        assertThat(other.get()).isEqualTo(0);           // 500/uk위반 없음
        assertThat(ok.get()).isEqualTo(n);              // 전부 200(멱등 재사용)
        assertThat(availableQty(skuId)).isEqualTo(9);   // 정확히 1회만 차감
        assertThat(reservationRowCount("pk-same", skuId)).isEqualTo(1);
    }

    // --- helpers ---

    /** N건을 CountDownLatch로 동시 발사하고 (statusCode, body)를 콜백으로 소비. */
    private void fire(int n, java.util.function.IntFunction<String> bodyFn,
                      java.util.function.ObjIntConsumer<String> onResponse) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    HttpResponse<String> resp = post("/v1/stock/reserve", bodyFn.apply(idx));
                    synchronized (onResponse) {
                        onResponse.accept(resp.body(), resp.statusCode());
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
    }

    private String readCodeUnchecked(String body) {
        try {
            return (String) om.readValue(body, Map.class).get("code");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long seedSku(String code, int stock) throws Exception {
        HttpResponse<String> seed = post("/v1/products", """
                {"name":"티셔츠","categoryId":%d,"skus":[{"skuCode":"%s","optionSummary":"opt","initialStock":%d,"price":1000}]}"""
                .formatted(CategoryFixtures.leafId(jdbc), code, stock));
        assertThat(seed.statusCode()).isEqualTo(200);
        Map<?, ?> body = om.readValue(seed.body(), Map.class);
        return ((Number) ((Map<?, ?>) ((List<?>) body.get("skus")).get(0)).get("skuId")).longValue();
    }

    private String reserveBody(String pk, long skuId, int qty) {
        return """
                {"paymentKey":"%s","items":[{"productId":%d,"skuId":%d,"qty":%d}]}"""
                .formatted(pk, productId(skuId), skuId, qty);
    }

    private long productId(long skuId) {
        return jdbc.queryForObject(
                "SELECT product_id FROM product_sku WHERE id = ?", Long.class, skuId);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-User-Role", "ADMIN")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private int reservedRowCount(long skuId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE sku_id = ? AND status = 'RESERVED'",
                Integer.class, skuId);
    }

    private int reservationRowCount(String paymentKey, long skuId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE payment_key = ? AND sku_id = ?",
                Integer.class, paymentKey, skuId);
    }
}
