package com.example.payment.integration;

import com.example.payment.application.service.CreatePaymentCommand;
import com.example.payment.application.service.PaymentCreateTxWriter;
import com.example.payment.infrastructure.scheduler.PaymentCompletedOutboxPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * payment.completed 아웃박스 통합 테스트 (SALE-01): 생성 TX 안 원자 INSERT + 폴 발행 + Z-suffix + 롤백⇒0행.
 *
 * <p>cancel 발행 스케줄러(@PostConstruct wake)는 mocked Redisson에서 NPE 나므로 INLINE 고정
 * (CreatePaymentReserveIntegrationTest 동일 관행). 새 PaymentCompletedOutboxPublisher는 @PostConstruct 없음.
 */
@Testcontainers
@SpringBootTest(properties = "cancel.publish.mode=INLINE")
@DisplayName("payment.completed 아웃박스 통합 테스트 (Testcontainers)")
class PaymentCompletedOutboxIntegrationTest {

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
    @Autowired PaymentCompletedOutboxPublisher publisher;
    @Autowired PaymentCreateTxWriter txWriter;
    @MockitoSpyBean com.example.payment.application.interfaces.PaymentEventOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;
    MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        RLock lock = mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        } catch (InterruptedException ignored) { }
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM payment_event_outbox");
        jdbcTemplate.update("DELETE FROM payment_item");
        jdbcTemplate.update("DELETE FROM payment");
    }

    private String requestJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "merchantId", 1,
            "pgType", "TOSS",
            "cancelPeriodDays", 90,
            "items", List.of(Map.of(
                "orderItemId", 10, "productId", 200, "itemName", "상품A",
                "itemAmount", 1, "skuId", 500, "quantity", 2))));
    }

    private void stubOrderVerifyAndReserve() {
        mockServer.expect(requestTo(containsString("/v1/orders/items:verify")))
            .andRespond(withSuccess("{\"orderId\":777}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("/v1/stock/reserve")))
            .andRespond(withSuccess("""
                {"reserved":true,"items":[{"skuId":500,"productId":200,
                "unitPrice":10000,"quantity":2}]}""", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("생성 TX 안 원자 INSERT: 결제 생성 → PENDING payment_event_outbox 1행, payload completedAt은 ...Z")
    void createPayment_writesOnePendingOutboxRow_withZSuffix() throws Exception {
        stubOrderVerifyAndReserve();
        mockMvc.perform(post("/v1/payments")
                .header("X-User-Id", "100")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson()))
            .andExpect(status().isOk());

        Integer pending = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_event_outbox WHERE status='PENDING'", Integer.class);
        assertThat(pending).isEqualTo(1);

        String payload = jdbcTemplate.queryForObject(
            "SELECT payload FROM payment_event_outbox", String.class);
        // MySQL JSON 컬럼은 키 순서/공백을 정규화하므로 문자열 매칭 대신 파싱해서 검증한다.
        var node = objectMapper.readTree(payload);
        assertThat(node.get("orderId").asLong()).isEqualTo(777L);
        // completedAt은 Instant.parse가 요구하는 트레일링 Z 형식이어야 한다.
        assertThat(node.get("completedAt").asText()).endsWith("Z");
        assertThat(node.get("totalAmount").asInt()).isEqualTo(20000);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT total_amount FROM payment", BigDecimal.class)).isEqualByComparingTo("20000");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT item_amount FROM payment_item", BigDecimal.class)).isEqualByComparingTo("20000");

        // 폴 발행 → PENDING→PUBLISHED
        publisher.publish();
        String status = jdbcTemplate.queryForObject(
            "SELECT status FROM payment_event_outbox", String.class);
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("롤백⇒0행: persist() 내 실패 시 payment/payment_item 과 함께 아웃박스 행도 0 (같은 TX)")
    void rollback_leavesZeroOutboxRows() {
        // insertPending을 실제 실행 후 예외 → persist() TX 롤백. 아웃박스 INSERT가 TX 안이면 그 행도 사라진다.
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new RuntimeException("forced failure after outbox insert");
        }).when(outboxRepository).insertPending(anyString(), anyString());

        CreatePaymentCommand command = new CreatePaymentCommand(
            1L, 100L, "TOSS", 90,
            List.of(new CreatePaymentCommand.Item(10L, 200L, "상품A", new BigDecimal("30000"), 500L, 2)));

        assertThatThrownBy(() ->
            txWriter.persist(command, "PAY-ROLLBACK-1", new BigDecimal("60000"), 777L))
            .isInstanceOf(RuntimeException.class);

        Integer payments = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment", Integer.class);
        Integer items = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_item", Integer.class);
        Integer outbox = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_event_outbox", Integer.class);
        assertThat(payments).isZero();
        assertThat(items).isZero();
        assertThat(outbox).isZero(); // dual-write 안전: 아웃박스 INSERT는 persist() TX 안
        Mockito.verify(outboxRepository).insertPending(eq("PAY-ROLLBACK-1"), anyString());
    }
}
