package com.example.product.integration;

import com.example.product.application.interfaces.PaymentQueryPort;
import com.example.product.application.service.OrphanReservationRecoveryService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * orphan 예약 복구 통합테스트 (RST-03, D-P3-3/D-P3-4). 실 MySQL(Testcontainers).
 *
 * <p>PaymentQueryPort 는 @MockitoBean 스텁 — 실 payment HTTP·Redisson 불필요. 서비스 레벨에서
 * recoverAll() 직접 호출로 스캔→조회→release 판정을 검증한다. created_at 은 JDBC 로 backdate 해
 * threshold(5분) 경계를 결정적으로 통제한다.
 *
 * <p>StockTracerIntegrationTest 조립 미러(WebApplicationContext + MockMvcBuilders) — seed·reserve 는
 * 기존 엔드포인트 재사용.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Orphan 예약 복구 (스캔→exists 조회→release/skip)")
class OrphanReservationRecoveryIntegrationTest {

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
    }

    @MockitoBean PaymentQueryPort paymentQueryPort;

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrphanReservationRecoveryService recoveryService;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    @DisplayName("orphan(exists=false, 5분+): release 되어 재고 복원 + status=RELEASED")
    void orphanReleasedAndStockRestored() throws Exception {
        String paymentKey = "PAY-ORPHAN-" + SEQ.incrementAndGet();
        long skuId = seedSku(10);
        reserve(paymentKey, skuId, 3);          // available 10→7, RESERVED
        assertThat(availableQty(skuId)).isEqualTo(7);
        backdate(paymentKey, minutesAgo(10));   // stale
        when(paymentQueryPort.exists(paymentKey)).thenReturn(false);

        recoveryService.recoverAll();

        assertThat(availableQty(skuId)).isEqualTo(10);           // 복원
        assertThat(status(paymentKey, skuId)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("정상(exists=true, 5분+): RESERVED 유지, 재고 불변")
    void committedPaymentKeptReserved() throws Exception {
        String paymentKey = "PAY-KEEP-" + SEQ.incrementAndGet();
        long skuId = seedSku(10);
        reserve(paymentKey, skuId, 3);
        backdate(paymentKey, minutesAgo(10));
        when(paymentQueryPort.exists(paymentKey)).thenReturn(true);

        recoveryService.recoverAll();

        assertThat(availableQty(skuId)).isEqualTo(7);            // 불변
        assertThat(status(paymentKey, skuId)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("최근(threshold 미만): exists=false여도 스캔 대상 아님 — 미복원")
    void recentReservationNotScanned() throws Exception {
        String paymentKey = "PAY-RECENT-" + SEQ.incrementAndGet();
        long skuId = seedSku(10);
        reserve(paymentKey, skuId, 3);
        backdate(paymentKey, minutesAgo(1));    // threshold(5분) 미만
        when(paymentQueryPort.exists(paymentKey)).thenReturn(false);

        recoveryService.recoverAll();

        assertThat(availableQty(skuId)).isEqualTo(7);            // 미복원
        assertThat(status(paymentKey, skuId)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("조회 예외(fail-safe): 해당 건 skip — release 안 함")
    void queryExceptionSkipsRelease() throws Exception {
        String paymentKey = "PAY-ERR-" + SEQ.incrementAndGet();
        long skuId = seedSku(10);
        reserve(paymentKey, skuId, 3);
        backdate(paymentKey, minutesAgo(10));   // stale (스캔 대상)
        when(paymentQueryPort.exists(paymentKey))
                .thenThrow(new RuntimeException("payment 조회 장애"));

        recoveryService.recoverAll();

        assertThat(availableQty(skuId)).isEqualTo(7);            // release 안 함
        assertThat(status(paymentKey, skuId)).isEqualTo("RESERVED");
    }

    // --- helpers ---

    private LocalDateTime minutesAgo(int m) {
        return LocalDateTime.now(ZoneOffset.UTC).minusMinutes(m);
    }

    private long seedSku(int initialStock) throws Exception {
        String code = "SKU-" + SEQ.incrementAndGet();
        MockHttpServletResponse seed = send("/v1/products", """
                {"name":"티셔츠","categoryId":%d,"skus":[{"skuCode":"%s","optionSummary":"M/Black","initialStock":%d,"price":1000}]}"""
                .formatted(CategoryFixtures.leafId(jdbc), code, initialStock));
        assertThat(seed.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(seed.getContentAsString(), Map.class);
        return ((Number) ((Map<?, ?>) ((java.util.List<?>) body.get("skus")).get(0)).get("skuId")).longValue();
    }

    private void reserve(String paymentKey, long skuId, int qty) throws Exception {
        MockHttpServletResponse res = send("/v1/stock/reserve", """
                {"paymentKey":"%s","items":[{"skuId":%d,"qty":%d}]}"""
                .formatted(paymentKey, skuId, qty));
        assertThat(res.getStatus()).isEqualTo(200);
    }

    private void backdate(String paymentKey, LocalDateTime createdAt) {
        int updated = jdbc.update(
                "UPDATE stock_reservation SET created_at = ? WHERE payment_key = ?",
                Timestamp.valueOf(createdAt), paymentKey);
        assertThat(updated).isPositive();
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

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }
}
