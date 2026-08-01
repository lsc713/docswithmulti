package com.example.order.integration;

import com.example.order.application.exception.NonRetryableException;
import com.example.order.application.interfaces.CancelRestoreDlqRepository;
import com.example.order.application.interfaces.OperationAlertPort;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase.Command;
import com.example.order.infrastructure.scheduler.CancelRestoreRedriveScheduler;
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
 * REDRIVE-02 + NonRetryable 즉시 DLQ + 멱등 no-op (Phase 1 product DeadEscalation IT 미러, order 도메인):
 * (a) NonRetryable → 재시도 없이 cancel_restore_dlq PENDING 적재 + alert.
 * (b) 재구동 반복 실패 → attempt_count 임계 도달 → DEAD 전이 + 에스컬레이션 alert.
 * (c) 이미 처리분(processed_cancel_event) 재구동 → 상태 재변경 없이 no-op RESOLVED(멱등).
 *
 * <p>임계는 @DynamicPropertySource 로 3 으로 낮춰 가속. 재구동은 scheduler.pollOnce 직접 호출.
 * order 는 @Bean(반환형=인터페이스)으로 배선되므로 concrete 대신 인터페이스 타입으로 spy.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore order-leg DEAD 에스컬레이션 + NonRetryable 즉시 DLQ + 멱등 no-op")
class CancelRestoreDeadEscalationIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("order");

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
    @Autowired CancelRestoreDlqRepository dlqRepository;
    @Autowired CancelRestoreRedriveScheduler redriveScheduler;

    @MockitoBean OperationAlertPort operationAlertPort;
    @MockitoSpyBean ProcessCancelledItemsUseCase processUseCase;

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
        }).when(processUseCase).execute(any(Command.class));
    }

    @Test
    @DisplayName("(a) NonRetryable: 재시도 없이 PENDING 적재(retry_count=0) + alert 1회")
    void nonRetryableGoesStraightToDlq() throws Exception {
        long itemId = seedOrderItem();
        String cancelRequestId = "9201";

        failMode.set("NONRETRYABLE");
        publish("payment.cancelled", cancelRequestId, payloadJson(cancelRequestId, itemId));

        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(dlqStatus(cancelRequestId)).isEqualTo("PENDING"));
        assertThat(retryCount(cancelRequestId)).isZero(); // 재시도 없이 즉시 DLQ
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(anyString());
    }

    @Test
    @DisplayName("(b) 재구동 반복 실패 → attempt_count 임계(3) 도달 → DEAD + 에스컬레이션 alert")
    void repeatedRedriveFailureReachesDead() {
        long itemId = seedOrderItem();
        String cancelRequestId = "9202";

        // PENDING 행 seed(재시도 소진 상태 모사)
        dlqRepository.upsertPending(cancelRequestId, "ORDER",
                payloadJson(cancelRequestId, itemId), 3, "seed");

        // 재구동이 계속 실패하도록 유지 → pollOnce 반복 → DEAD 도달
        failMode.set("RETRYABLE");
        for (int i = 0; i < 10 && !"DEAD".equals(dlqStatus(cancelRequestId)); i++) {
            redriveScheduler.pollOnce(Instant.now().plusSeconds(5));
        }

        assertThat(dlqStatus(cancelRequestId)).isEqualTo("DEAD");
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(contains("DEAD"));
    }

    @Test
    @DisplayName("(c) 이미 처리분 재구동 → 상태 재변경 없이 no-op RESOLVED(멱등)")
    void alreadyProcessedRedriveIsNoOpResolved() {
        long orderId = seedOrder();
        long itemId = seedItem(orderId);
        String cancelRequestId = "9203";

        // 이미 처리됨 표시 + PENDING dlq 행 (실제 취소는 미적용 → 아이템 ACTIVE 유지)
        jdbc.update("INSERT INTO processed_cancel_event(cancel_request_id, processed_at) VALUES (?, ?)",
                cancelRequestId, new Timestamp(System.currentTimeMillis()));
        dlqRepository.upsertPending(cancelRequestId, "ORDER",
                payloadJson(cancelRequestId, itemId), 3, "seed");

        failMode.set("NONE"); // 실제 execute 위임 → processed 게이트로 no-op
        redriveScheduler.pollOnce(Instant.now().plusSeconds(5));

        assertThat(dlqStatus(cancelRequestId)).isEqualTo("RESOLVED");
        // processed 게이트로 execute 내부 no-op → 아이템 상태 재변경 없음(과다 처리 없음)
        assertThat(itemStatus(itemId)).isEqualTo("ACTIVE");
        assertThat(orderStatus(orderId)).isEqualTo("DELIVERY_WAITING");
    }

    // --- helpers ---

    private long seedOrder() {
        jdbc.update("INSERT INTO orders(user_id, status) VALUES (1, 'DELIVERY_WAITING')");
        return jdbc.queryForObject("SELECT id FROM orders ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long seedItem(long orderId) {
        jdbc.update("INSERT INTO order_item(order_id, product_id, item_name, price, status) "
                + "VALUES (?, 100, 'T', 30000, 'ACTIVE')", orderId);
        return jdbc.queryForObject(
                "SELECT id FROM order_item WHERE order_id = ? ORDER BY id DESC LIMIT 1", Long.class, orderId);
    }

    private long seedOrderItem() {
        return seedItem(seedOrder());
    }

    private String payloadJson(String cancelRequestId, long itemId) {
        return ("""
                {"cancelRequestId":"%s","paymentKey":"PAY-DEAD","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":%d,"itemAmount":30000}],\
                "cancelledAt":"2026-08-01T00:00:00Z"}""").formatted(cancelRequestId, itemId);
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

    private String itemStatus(long itemId) {
        return jdbc.queryForObject("SELECT status FROM order_item WHERE id = ?", String.class, itemId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
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
