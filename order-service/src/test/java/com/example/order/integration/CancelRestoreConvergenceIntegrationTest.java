package com.example.order.integration;

import com.example.order.application.interfaces.OperationAlertPort;
import com.example.order.application.service.CancelRestoreRedriveService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * order-leg 수렴 트레이서 e2e (DLQ-01/DLQ-02/REDRIVE-01, Phase 1 product Convergence IT 미러):
 * 핸들러가 재시도 소진(count≥3)까지 실패 → cancel_restore_dlq(leg=ORDER, PENDING) 멱등 적재 + alert →
 * 결함 제거 후 재구동 스케줄러가 주문 상태를 동기화하고 status=RESOLVED 로 전이. <b>이벤트 손실 0</b>.
 *
 * <p>결함 주입: ProcessCancelledItemsUseCase 를 @MockitoSpyBean 으로 감싸 fail 플래그가 켜진 동안
 * execute 가 예외를 던지게 한다. 플래그를 끄면 실제 상태전이 위임.
 * order 는 @Bean(반환형=인터페이스)으로 배선되므로 concrete 대신 인터페이스 타입으로 spy(양 경로 커버).
 * 재구동은 scheduler.pollOnce 직접 호출(product 동형).
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore order-leg 수렴 (핸들러 실패→DLQ PENDING+alert→재구동→주문 CANCELLED→RESOLVED, 손실0)")
class CancelRestoreConvergenceIntegrationTest {

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
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CancelRestoreRedriveScheduler redriveScheduler;
    @Autowired CancelRestoreRedriveService redriveService; // 컨텍스트 배선 검증

    @MockitoBean OperationAlertPort operationAlertPort;
    @MockitoSpyBean ProcessCancelledItemsUseCase processUseCase;

    final AtomicBoolean failHandler = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        // fail 플래그가 켜진 동안 execute 예외 → 재시도/DLQ 경로 진입. 꺼지면 실제 상태전이 위임.
        Mockito.doAnswer(inv -> {
            if (failHandler.get()) {
                throw new RuntimeException("주입된 일시 장애 — 주문 상태 동기화 실패");
            }
            return inv.callRealMethod();
        }).when(processUseCase).execute(any(Command.class));
    }

    @Test
    @DisplayName("DLQ-01/02/REDRIVE-01: 실패→PENDING 멱등 적재+alert→재구동→주문 CANCELLED+RESOLVED, 손실0")
    void failThenDlqThenRedriveConverges() throws Exception {
        // 1. seed 주문 + 2개 아이템(ACTIVE)
        jdbc.update("INSERT INTO orders(user_id, status) VALUES (1, 'DELIVERY_WAITING')");
        Long orderId = jdbc.queryForObject("SELECT id FROM orders ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO order_item(order_id, product_id, item_name, price, status) "
                + "VALUES (?, 100, 'T-1', 30000, 'ACTIVE')", orderId);
        jdbc.update("INSERT INTO order_item(order_id, product_id, item_name, price, status) "
                + "VALUES (?, 101, 'T-2', 20000, 'ACTIVE')", orderId);
        Long itemId1 = jdbc.queryForObject(
                "SELECT id FROM order_item WHERE order_id = ? ORDER BY id ASC LIMIT 1", Long.class, orderId);
        Long itemId2 = jdbc.queryForObject(
                "SELECT id FROM order_item WHERE order_id = ? ORDER BY id DESC LIMIT 1", Long.class, orderId);

        String cancelRequestId = "9002";

        // 2. 결함 주입 ON → payment.cancelled 발행 → 재시도 소진(count≥3) → DLQ 경로
        failHandler.set(true);
        String json = payloadJson(cancelRequestId, itemId1, itemId2);
        publish("payment.cancelled", cancelRequestId, json);

        // 3. cancel_restore_dlq 에 leg=ORDER PENDING 행이 UK 로 1건 멱등 적재
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(dlqCount(cancelRequestId, "PENDING")).isEqualTo(1));
        assertThat(orderStatus(orderId)).isEqualTo("DELIVERY_WAITING"); // 아직 미처리

        // DLQ-02: count≥3 DLQ 경로에서 alert 발송됨
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(anyString());

        // 4. 결함 제거 → 재구동 스케줄러(pollOnce 직접 호출, 백오프 우회 위해 미래 cutoff)
        failHandler.set(false);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    redriveScheduler.pollOnce(Instant.now().plusSeconds(1));
                    assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");        // 주문 취소 수렴(손실 0)
                    assertThat(activeItemCount(orderId)).isZero();                   // 모든 아이템 CANCELLED
                    assertThat(dlqCount(cancelRequestId, "RESOLVED")).isEqualTo(1);  // RESOLVED 전이
                });
    }

    private String payloadJson(String cancelRequestId, long itemId1, long itemId2) {
        return ("""
                {"cancelRequestId":"%s","paymentKey":"PAY-CONV-1","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":%d,"itemAmount":30000},\
                {"paymentItemId":2,"orderItemId":%d,"itemAmount":20000}],\
                "cancelledAt":"2026-08-01T00:00:00Z"}""").formatted(cancelRequestId, itemId1, itemId2);
    }

    private int dlqCount(String cancelRequestId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cancel_restore_dlq WHERE cancel_request_id = ? AND leg = 'ORDER' AND status = ?",
                Integer.class, cancelRequestId, status);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int activeItemCount(long orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_item WHERE order_id = ? AND status <> 'CANCELLED'", Integer.class, orderId);
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
