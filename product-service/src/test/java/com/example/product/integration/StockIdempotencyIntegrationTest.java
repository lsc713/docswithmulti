package com.example.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * reserve 멱등(W1, D-P1-4) + 다중아이템 원자 롤백(D-P1-3) 순차 회귀 — 실 MySQL(Testcontainers).
 * <p>같은 paymentKey+sku 재요청은 INSERT-ON-DUPLICATE 게이트로 재차감 없이 재사용(멱등),
 * 다중아이템 중 하나라도 부족하면 전-items 롤백(부분 예약 없음).
 * <p>{@code withUrlParam("useAffectedRows","true")}: winner/loser 판별 전제(기본 false면 no-op도 1 반환).
 * <p>동시 same-key burst는 StockConcurrencyIntegrationTest(Task 3)에서 별도 회귀. Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Stock reserve 멱등 + 다중아이템 원자 롤백")
class StockIdempotencyIntegrationTest {

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

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    @DisplayName("같은 paymentKey 재시도는 최초 가격 스냅샷을 반환하고 재차감하지 않는다")
    void reserveIsIdempotentOnSamePaymentKey() throws Exception {
        long skuId = seedSku("IDEM-1", 10, 10_000);
        long productId = productId(skuId);

        MockHttpServletResponse first = send("/v1/stock/reserve", """
                {"paymentKey":"pk-1","items":[{"productId":%d,"skuId":%d,"qty":3}]}"""
                .formatted(productId, skuId));
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(reservedUnitPrice(first)).isEqualTo(10_000);
        assertThat(availableQty(skuId)).isEqualTo(7);

        jdbc.update("UPDATE product_sku SET price = 20000 WHERE id = ?", skuId);

        // 재요청: INSERT-ON-DUPLICATE 게이트 → loser affected=0 → 차감 생략(재사용)
        MockHttpServletResponse second = send("/v1/stock/reserve", """
                {"paymentKey":"pk-1","items":[{"productId":%d,"skuId":%d,"qty":3}]}"""
                .formatted(productId, skuId));
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(reservedUnitPrice(second)).isEqualTo(10_000);

        assertThat(availableQty(skuId)).isEqualTo(7); // 재차감 없음(멱등)
        assertThat(reservationCount("pk-1", skuId)).isEqualTo(1); // 중복행 없음
        assertThat(snapshotPrice("pk-1", skuId)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("SKU가 요청 상품 소속이 아니면 400이고 재고와 예약은 변하지 않는다")
    void rejectsSkuOwnedByAnotherProduct() throws Exception {
        long skuA = seedSku("OWNER-A", 5, 10_000);
        long skuB = seedSku("OWNER-B", 5, 20_000);

        MockHttpServletResponse response = send("/v1/stock/reserve", """
                {"paymentKey":"pk-owner","items":[{"productId":%d,"skuId":%d,"qty":2}]}"""
                .formatted(productId(skuB), skuA));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(availableQty(skuA)).isEqualTo(5);
        assertThat(reservationCount("pk-owner", skuA)).isZero();
    }

    @Test
    @DisplayName("D-P1-3: 다중아이템 중 하나 부족 → 409, 앞선 item 차감 롤백(A=5), pk 예약행 0개")
    void multiItemReserveRollsBackAtomically() throws Exception {
        long skuA = seedSku("ATOM-A", 5, 1_000);
        long skuB = seedSku("ATOM-B", 1, 2_000);

        // A(qty2, 충분) 다음 B(qty5, 부족) → B에서 예외 → 전체 롤백
        MockHttpServletResponse resp = send("/v1/stock/reserve", """
                {"paymentKey":"pk-2","items":[{"productId":%d,"skuId":%d,"qty":2},
                {"productId":%d,"skuId":%d,"qty":5}]}"""
                .formatted(productId(skuA), skuA, productId(skuB), skuB));
        assertThat(resp.getStatus()).isEqualTo(409);
        assertThat(om.readValue(resp.getContentAsString(), Map.class).get("code")).isEqualTo("STOCK_001");

        assertThat(availableQty(skuA)).isEqualTo(5); // A 차감 롤백
        assertThat(availableQty(skuB)).isEqualTo(1);
        assertThat(reservationCount("pk-2", skuA)).isEqualTo(0); // 부분 예약 없음
        assertThat(reservationCount("pk-2", skuB)).isEqualTo(0);
    }

    private long seedSku(String code, int stock, long price) throws Exception {
        MockHttpServletResponse seed = send("/v1/products", """
                {"name":"티셔츠","categoryId":%d,"skus":[{"skuCode":"%s","optionSummary":"opt","initialStock":%d,"price":%d}]}"""
                .formatted(CategoryFixtures.leafId(jdbc), code, stock, price));
        assertThat(seed.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(seed.getContentAsString(), Map.class);
        return ((Number) ((Map<?, ?>) ((java.util.List<?>) body.get("skus")).get(0)).get("skuId")).longValue();
    }

    private long productId(long skuId) {
        return jdbc.queryForObject(
                "SELECT product_id FROM product_sku WHERE id = ?", Long.class, skuId);
    }

    private long reservedUnitPrice(MockHttpServletResponse response) throws Exception {
        Map<?, ?> body = om.readValue(response.getContentAsString(), Map.class);
        Map<?, ?> item = (Map<?, ?>) ((java.util.List<?>) body.get("items")).get(0);
        return ((Number) item.get("unitPrice")).longValue();
    }

    private long snapshotPrice(String paymentKey, long skuId) {
        return jdbc.queryForObject(
                "SELECT unit_price FROM stock_reservation WHERE payment_key = ? AND sku_id = ?",
                Long.class, paymentKey, skuId);
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private int reservationCount(String paymentKey, long skuId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE payment_key = ? AND sku_id = ?",
                Integer.class, paymentKey, skuId);
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "ADMIN")
                        .content(body))
                .andReturn().getResponse();
    }
}
