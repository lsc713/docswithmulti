package com.example.payment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 결제 생성 → product reserve(TX 밖) → persist 경로 통합 테스트 (tracer e2e).
 *
 * 실제 MySQL(Testcontainers) + MockRestServiceServer(공유 RestTemplate)로
 * reserve 200→저장 / 409→거부·미저장 / CB OPEN→거부·미저장을 end-to-end 증명한다(D-P2-1/2).
 */
@Testcontainers
// 생성 경로는 cancel 이벤트를 발행하지 않는다. OUTBOX 발행 스케줄러(@PostConstruct가 mocked Redisson에
// wake 구독 시 NPE)를 비활성화하려 INLINE 고정(CancelFlowIntegrationTest 동일 관행). 취소 코어 무변경.
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("CreatePayment reserve 통합 테스트 (Testcontainers)")
class CreatePaymentReserveIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // external.* URL은 test/resources/application.yml에서 제공(더미). reserve만 MockRestServiceServer로 가로챈다.
    }

    @MockitoBean KafkaTemplate<String, String> kafkaTemplate; // Kafka 실제 발행 차단
    @MockitoBean RedissonClient redissonClient;               // Redis 없이 스케줄러 빈 생성

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired RestTemplate restTemplate;                     // 공유 빈 — ProductStockHttpClient도 이 빈 주입
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CircuitBreaker productServiceCircuitBreaker;   // 프로덕션 CB 빈(시나리오 C)

    private final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;
    MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_item");
        jdbcTemplate.update("DELETE FROM payment");
    }

    private static final long VERIFIED_ORDER_ID = 777L;

    private String requestJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "merchantId", 1,
            "pgType", "TOSS",
            "cancelPeriodDays", 90,
            "items", List.of(Map.of(
                "orderItemId", 10,
                "productId", 200,
                "itemName", "상품A",
                "itemAmount", 30000,
                "skuId", 500,
                "quantity", 2
            ))
        ));
    }

    /** order verify(200 {orderId})를 reserve보다 먼저 스텁한다(MockRestServiceServer 기본 순서 매칭). */
    private void stubOrderVerifySuccess() {
        mockServer.expect(requestTo(containsString("/v1/orders/items:verify")))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(header("X-User-Id", "100"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.orderItemIds[0]").value(10))
            .andRespond(withSuccess("{\"orderId\":" + VERIFIED_ORDER_ID + "}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("A: verify 200(orderId) → reserve보다 먼저 X-User-Id·orderItemIds로 호출, 200 저장, payment.order_id/payment_item.sku_id·quantity 영속")
    void reserveSuccess_persists() throws Exception {
        stubOrderVerifySuccess();
        mockServer.expect(requestTo(containsString("/v1/stock/reserve")))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.paymentKey").exists())
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.items[0].skuId").value(500))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.items[0].qty").value(2))
            .andRespond(withSuccess());

        mockMvc.perform(post("/v1/payments")
                .header("X-User-Id", "100")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson()))
            .andExpect(status().isOk());

        Integer payments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
        assertThat(payments).isEqualTo(1);
        Map<String, Object> payment = jdbcTemplate.queryForMap("SELECT order_id FROM payment");
        assertThat(((Number) payment.get("order_id")).longValue()).isEqualTo(VERIFIED_ORDER_ID);
        Map<String, Object> item = jdbcTemplate.queryForMap(
            "SELECT sku_id, quantity FROM payment_item");
        assertThat(((Number) item.get("sku_id")).longValue()).isEqualTo(500L);
        assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(2);

        // MockRestServiceServer는 스텁 등록 순서대로 요청을 매칭한다 — verify 스텁이 먼저 소진됐다는 것 자체가
        // verify가 reserve보다 먼저 호출됐음을 증명한다(PLINK-03).
        mockServer.verify();
    }

    @Test
    @DisplayName("B: reserve 409 → 409 STOCK_INSUFFICIENT, payment 미저장(fail-closed)")
    void reserveConflict_rejected() throws Exception {
        stubOrderVerifySuccess();
        mockServer.expect(requestTo(containsString("/v1/stock/reserve")))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"STOCK_INSUFFICIENT\"}"));

        mockMvc.perform(post("/v1/payments")
                .header("X-User-Id", "100")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson()))
            .andExpect(status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("STOCK_INSUFFICIENT"));

        Integer payments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
        assertThat(payments).isZero();
    }

    @Test
    @DisplayName("C: product CircuitBreaker OPEN → 503 PRODUCT_SERVICE_UNAVAILABLE, payment 미저장(fail-closed)")
    void circuitBreakerOpen_rejected() throws Exception {
        stubOrderVerifySuccess();
        // DEFAULT_CONFIG minimumNumberOfCalls 때문에 스텁 실패로는 OPEN 불가 → 직접 강제 전이.
        productServiceCircuitBreaker.transitionToOpenState();
        try {
            mockMvc.perform(post("/v1/payments")
                    .header("X-User-Id", "100")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));

            Integer payments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
            assertThat(payments).isZero();
        } finally {
            productServiceCircuitBreaker.transitionToClosedState();
        }
    }
}
