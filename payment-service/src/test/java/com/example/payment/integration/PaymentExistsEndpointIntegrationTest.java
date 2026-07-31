package com.example.payment.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /v1/payments/{paymentKey}/exists 통합 테스트 (RST-03, D-P3-3/D-P3-5).
 *
 * 커밋된 payment → {exists:true}, 미존재 paymentKey → {exists:false} 200 반환을
 * 실제 MySQL(Testcontainers)로 증명. 기존 create 조립 재사용 — 취소/생성 코어 무변경(read-only).
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("PaymentExists 엔드포인트 통합 테스트 (Testcontainers)")
class PaymentExistsEndpointIntegrationTest {

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
    }

    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;
    @MockitoBean RedissonClient redissonClient;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired RestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

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

    private String createPaymentAndGetKey() throws Exception {
        mockServer.expect(requestTo(containsString("/v1/stock/reserve")))
            .andRespond(withSuccess());

        String body = objectMapper.writeValueAsString(Map.of(
            "merchantId", 1,
            "userId", 100,
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

        String response = mockMvc.perform(post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(response);
        return node.get("paymentKey").asText();
    }

    @Test
    @DisplayName("커밋된 paymentKey → 200 {exists:true}")
    void existingPaymentKey_returnsTrue() throws Exception {
        String paymentKey = createPaymentAndGetKey();

        mockMvc.perform(get("/v1/payments/{paymentKey}/exists", paymentKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    @DisplayName("미존재 paymentKey → 200 {exists:false}")
    void unknownPaymentKey_returnsFalse() throws Exception {
        mockMvc.perform(get("/v1/payments/{paymentKey}/exists", "pay_does_not_exist_xyz"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(false));
    }
}
