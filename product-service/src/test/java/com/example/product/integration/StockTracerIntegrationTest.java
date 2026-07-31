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
 * Tracer end-to-end: seed → reserve(성공, 차감) → reserve(부족, 미차감 409)를 실제 MySQL(Testcontainers)로
 * presentation→application→domain←infrastructure→product_db 전 레이어 관통 (STOCK-01/02/03, D-P1-3).
 *
 * Boot 4.0.5: @AutoConfigureMockMvc / TestRestTemplate 미제공 →
 * WebApplicationContext + MockMvcBuilders.webAppContextSetup 으로 MockMvc 직접 조립 (AuthIntegrationTest 패턴).
 * Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Stock tracer (seed→reserve→오버셀 방지)")
class StockTracerIntegrationTest {

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

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    @DisplayName("STOCK-01/02/03: seed(재고5) → reserve3(200, available=2) → reserve5(409, 미차감)")
    void seedThenReserveThenOversellRejected() throws Exception {
        // 1. STOCK-02: product+SKU+초기재고5 seed → 200, skuId 반환
        MockHttpServletResponse seed = send("/v1/products", """
                {"name":"티셔츠","skus":[{"skuCode":"TS-M-BLK","optionSummary":"M/Black","initialStock":5}]}""");
        assertThat(seed.getStatus()).isEqualTo(200);
        Map<?, ?> seedBody = om.readValue(seed.getContentAsString(), Map.class);
        long skuId = ((Number) ((Map<?, ?>) ((java.util.List<?>) seedBody.get("skus")).get(0)).get("skuId")).longValue();
        assertThat(skuId).isPositive();
        assertThat(availableQty(skuId)).isEqualTo(5);

        // 2. STOCK-03 happy: reserve qty=3 → 200 reserved:true, available 5→2 (원자 차감)
        MockHttpServletResponse ok = send("/v1/stock/reserve", """
                {"paymentKey":"PAY-1","items":[{"skuId":%d,"qty":3}]}""".formatted(skuId));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(om.readValue(ok.getContentAsString(), Map.class).get("reserved")).isEqualTo(true);
        assertThat(availableQty(skuId)).isEqualTo(2);

        // 3. STOCK-03 오버셀 방지: 다른 paymentKey로 reserve qty=5 (남은 2 < 5) → 409 STOCK_001, 미차감
        MockHttpServletResponse insufficient = send("/v1/stock/reserve", """
                {"paymentKey":"PAY-2","items":[{"skuId":%d,"qty":5}]}""".formatted(skuId));
        assertThat(insufficient.getStatus()).isEqualTo(409);
        assertThat(om.readValue(insufficient.getContentAsString(), Map.class).get("code")).isEqualTo("STOCK_001");
        assertThat(availableQty(skuId)).isEqualTo(2); // 부족 시 미차감(롤백)
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }
}
