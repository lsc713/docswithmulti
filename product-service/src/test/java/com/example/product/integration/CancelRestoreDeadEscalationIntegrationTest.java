package com.example.product.integration;

import com.example.product.application.exception.NonRetryableException;
import com.example.product.application.interfaces.CancelRestoreDlqRepository;
import com.example.product.application.interfaces.OperationAlertPort;
import com.example.product.application.service.ProcessCancelledStockService;
import com.example.product.application.service.StockService;
import com.example.product.application.usecase.ProcessCancelledStockUseCase.Command;
import com.example.product.infrastructure.scheduler.CancelRestoreRedriveScheduler;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

/**
 * REDRIVE-02 + NonRetryable 즉시 DLQ + 멱등 no-op (설계 §수정3):
 * (a) NonRetryable → 재시도 없이 cancel_restore_dlq PENDING 적재 + alert.
 * (b) 재구동 반복 실패 → attempt_count 임계 도달 → DEAD 전이 + 에스컬레이션 alert.
 * (c) 이미 처리분(processed_cancel_event) 재구동 → release 재호출 없이 no-op RESOLVED(멱등).
 *
 * <p>임계는 @DynamicPropertySource 로 3 으로 낮춰 가속. 재구동은 scheduler.pollOnce 직접 호출.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore DEAD 에스컬레이션 + NonRetryable 즉시 DLQ + 멱등 no-op")
class CancelRestoreDeadEscalationIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("cancel-restore.redrive.dead-threshold", () -> "3"); // 가속
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stockService;
    @Autowired CancelRestoreDlqRepository dlqRepository;
    @Autowired CancelRestoreRedriveScheduler redriveScheduler;

    @MockitoBean OperationAlertPort operationAlertPort;
    @MockitoSpyBean ProcessCancelledStockService processService;

    /** NONE=실제, RETRYABLE=일시 장애, NONRETRYABLE=데이터 오류(즉시 DLQ). */
    final AtomicReference<String> failMode = new AtomicReference<>("NONE");

    static class TestNonRetryable extends RuntimeException implements NonRetryableException {
        TestNonRetryable(String m) { super(m); }
    }

    @BeforeEach
    void setUp() {
        failMode.set("NONE");
        Mockito.doAnswer(inv -> switch (failMode.get()) {
            case "RETRYABLE" -> { throw new RuntimeException("주입된 일시 장애"); }
            case "NONRETRYABLE" -> { throw new TestNonRetryable("주입된 데이터 오류(NonRetryable)"); }
            default -> inv.callRealMethod();
        }).when(processService).execute(any(Command.class));
    }

    @Test
    @DisplayName("(a) NonRetryable: 재시도 없이 PENDING 적재(retry_count=0) + alert 1회")
    void nonRetryableGoesStraightToDlq() throws Exception {
        long skuId = seedSku("TS-DEAD-A", 5);
        String paymentKey = "PAY-DEAD-A";
        String cancelRequestId = "9201";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));

        failMode.set("NONRETRYABLE");
        publish("payment.cancelled", cancelRequestId, payloadJson(cancelRequestId, paymentKey, skuId, 3));

        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(dlqStatus(cancelRequestId)).isEqualTo("PENDING"));
        assertThat(retryCount(cancelRequestId)).isZero(); // 재시도 없이 즉시 DLQ
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(anyString());
    }

    @Test
    @DisplayName("(b) 재구동 반복 실패 → attempt_count 임계(3) 도달 → DEAD + 에스컬레이션 alert")
    void repeatedRedriveFailureReachesDead() {
        long skuId = seedSku("TS-DEAD-B", 5);
        String paymentKey = "PAY-DEAD-B";
        String cancelRequestId = "9202";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));

        // PENDING 행 seed(재시도 소진 상태 모사)
        dlqRepository.upsertPending(cancelRequestId, "STOCK",
                payloadJson(cancelRequestId, paymentKey, skuId, 3), 3, "seed");

        // 재구동이 계속 실패하도록 유지 → pollOnce 반복 → DEAD 도달
        failMode.set("RETRYABLE");
        for (int i = 0; i < 10 && !"DEAD".equals(dlqStatus(cancelRequestId)); i++) {
            redriveScheduler.pollOnce(Instant.now().plusSeconds(5));
        }

        assertThat(dlqStatus(cancelRequestId)).isEqualTo("DEAD");
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(contains("DEAD"));
    }

    @Test
    @DisplayName("(c) 이미 처리분 재구동 → release 재호출 없이 no-op RESOLVED(멱등)")
    void alreadyProcessedRedriveIsNoOpResolved() {
        long skuId = seedSku("TS-DEAD-C", 5);
        String paymentKey = "PAY-DEAD-C";
        String cancelRequestId = "9203";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(skuId)).isEqualTo(2); // RESERVED, 미복원

        // 이미 처리됨 표시 + PENDING dlq 행
        jdbc.update("INSERT INTO processed_cancel_event(cancel_request_id, processed_at) VALUES (?, ?)",
                cancelRequestId, new Timestamp(System.currentTimeMillis()));
        dlqRepository.upsertPending(cancelRequestId, "STOCK",
                payloadJson(cancelRequestId, paymentKey, skuId, 3), 3, "seed");

        failMode.set("NONE"); // 실제 execute 위임 → processed 게이트로 no-op
        redriveScheduler.pollOnce(Instant.now().plusSeconds(5));

        assertThat(dlqStatus(cancelRequestId)).isEqualTo("RESOLVED");
        assertThat(availableQty(skuId)).isEqualTo(2); // release 재호출 없음 → 재고 불변(과다 복원 없음)
    }

    // --- helpers ---

    private long seedSku(String code, int stock) {
        jdbc.update("INSERT INTO product(name, category_id) VALUES ('티셔츠', ?)", CategoryFixtures.leafId(jdbc));
        Long productId = jdbc.queryForObject("SELECT id FROM product ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_sku(product_id, sku_code, option_summary) VALUES (?, ?, 'M/Black')",
                productId, code);
        long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = ?", Long.class, code);
        jdbc.update("INSERT INTO product_stock(sku_id, available_qty) VALUES (?, ?)", skuId, stock);
        return skuId;
    }

    private String payloadJson(String cancelRequestId, String paymentKey, long skuId, int qty) {
        return ("""
                {"cancelRequestId":%s,"paymentKey":"%s","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":10,"itemAmount":30000,"skuId":%d,"quantity":%d}],\
                "cancelledAt":"2026-08-01T00:00:00Z"}""").formatted(cancelRequestId, paymentKey, skuId, qty);
    }

    private String dlqStatus(String cancelRequestId) {
        List<String> s = jdbc.queryForList(
                "SELECT status FROM cancel_restore_dlq WHERE cancel_request_id = ?", String.class, cancelRequestId);
        return s.isEmpty() ? null : s.get(0);
    }

    private int retryCount(String cancelRequestId) {
        return jdbc.queryForObject(
                "SELECT retry_count FROM cancel_restore_dlq WHERE cancel_request_id = ?", Integer.class, cancelRequestId);
    }

    private int availableQty(long skuId) {
        return jdbc.queryForObject(
                "SELECT available_qty FROM product_stock WHERE sku_id = ?", Integer.class, skuId);
    }

    private void publish(String topic, String key, String value) throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }
}
