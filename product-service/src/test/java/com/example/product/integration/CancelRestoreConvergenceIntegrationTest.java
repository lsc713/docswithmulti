package com.example.product.integration;

import com.example.product.application.interfaces.OperationAlertPort;
import com.example.product.application.service.CancelRestoreRedriveService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 수렴 트레이서 e2e (DLQ-01/DLQ-02/REDRIVE-01, 설계 §수정2·수정3):
 * 핸들러가 재시도 소진(count≥3)까지 실패 → cancel_restore_dlq(leg=STOCK, PENDING) 멱등 적재 + alert →
 * 결함 제거 후 재구동 스케줄러가 재고를 복원하고 status=RESOLVED 로 전이. <b>이벤트 손실 0</b>.
 *
 * <p>결함 주입: ProcessCancelledStockUseCase 를 @MockitoSpyBean 으로 감싸 fail 플래그가 켜진 동안
 * execute 가 예외를 던지게 한다(가장 단순한 관측 가능 방법). 플래그를 끄면 실제 release 위임.
 * 재구동은 @Scheduled(락 mock=false 로 skip) 대신 scheduler.pollOnce 직접 호출(orphan 테스트 동형).
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore 수렴 (핸들러 실패→DLQ PENDING+alert→재구동→복원→RESOLVED, 손실0)")
class CancelRestoreConvergenceIntegrationTest {

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
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stockService;
    @Autowired CancelRestoreRedriveScheduler redriveScheduler;
    @Autowired CancelRestoreRedriveService redriveService; // 컨텍스트 배선 검증

    @MockitoBean OperationAlertPort operationAlertPort;
    @MockitoSpyBean ProcessCancelledStockService processService;

    final AtomicBoolean failHandler = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        // fail 플래그가 켜진 동안 execute 예외 → 재시도/DLQ 경로 진입. 꺼지면 실제 release 위임.
        Mockito.doAnswer(inv -> {
            if (failHandler.get()) {
                throw new RuntimeException("주입된 일시 장애 — 재고 복원 실패");
            }
            return inv.callRealMethod();
        }).when(processService).execute(any(Command.class));
    }

    @Test
    @DisplayName("DLQ-01/02/REDRIVE-01: 실패→PENDING 멱등 적재+alert→재구동→재고5 복원+RESOLVED, 손실0")
    void failThenDlqThenRedriveConverges() throws Exception {
        // 1. seed 재고 5 → reserve 3 → available 2
        jdbc.update("INSERT INTO product(name, category_id) VALUES ('티셔츠', ?)", CategoryFixtures.leafId(jdbc));
        Long productId = jdbc.queryForObject("SELECT id FROM product ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_sku(product_id, sku_code, option_summary) VALUES (?, 'TS-CV-1', 'M/Black')",
                productId);
        long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = 'TS-CV-1'", Long.class);
        jdbc.update("INSERT INTO product_stock(sku_id, available_qty) VALUES (?, 5)", skuId);

        String paymentKey = "PAY-CONV-1";
        String cancelRequestId = "9002";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(skuId)).isEqualTo(2);

        // 2. 결함 주입 ON → payment.cancelled 발행 → 재시도 소진(count≥3) → DLQ 경로
        failHandler.set(true);
        String json = ("""
                {"cancelRequestId":%s,"paymentKey":"%s","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":10,"itemAmount":30000,"skuId":%d,"quantity":3}],\
                "cancelledAt":"2026-08-01T00:00:00Z"}""").formatted(cancelRequestId, paymentKey, skuId);
        publish("payment.cancelled", cancelRequestId, json);

        // 3. cancel_restore_dlq 에 leg=STOCK PENDING 행이 UK 로 1건 멱등 적재
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(dlqCount(cancelRequestId, "PENDING")).isEqualTo(1));
        assertThat(availableQty(skuId)).isEqualTo(2); // 아직 미복원

        // DLQ-02: count≥3 DLQ 경로에서 alert 발송됨
        Mockito.verify(operationAlertPort, Mockito.atLeastOnce()).alert(anyString());

        // 4. 결함 제거 → 재구동 스케줄러(pollOnce 직접 호출, 백오프 우회 위해 미래 cutoff)
        failHandler.set(false);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    redriveScheduler.pollOnce(Instant.now().plusSeconds(1));
                    assertThat(availableQty(skuId)).isEqualTo(5);              // 재고 복원(손실 0)
                    assertThat(dlqCount(cancelRequestId, "RESOLVED")).isEqualTo(1); // RESOLVED 전이
                });
    }

    private int dlqCount(String cancelRequestId, String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cancel_restore_dlq WHERE cancel_request_id = ? AND leg = 'STOCK' AND status = ?",
                Integer.class, cancelRequestId, status);
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
