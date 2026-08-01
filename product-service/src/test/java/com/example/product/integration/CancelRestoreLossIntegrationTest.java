package com.example.product.integration;

import com.example.product.application.service.ProcessCancelledStockService;
import com.example.product.application.service.StockService;
import com.example.product.application.usecase.ProcessCancelledStockUseCase.Command;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;

/**
 * LOSS-01 무손실 (설계 §수정1): 재발행 send 가 브로커에서 확인될 때까지 원본을 ack 하지 않는다.
 *
 * <p>결정적 회복형 결함 주입: KafkaTemplate 을 @MockitoSpyBean 으로 감싸 retry 토픽 send 를
 * failRetrySend 플래그가 켜진 동안 실패시킨다(핸들러도 failHandler 로 실패 → 재시도 경로 진입).
 * → publishToRetry 의 .get(5s) 예외 전파 → 컨슈머 원본 미ack → 에러핸들러 재전달(execute 재호출).
 * 결함 제거 후 원본이 재전달되어 최종 처리(재고 복원)됨을 검증 — <b>이벤트 손실 0</b>.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore 무손실 (retry send 실패→원본 미ack→재전달→회복 후 처리, 손실0)")
class CancelRestoreLossIntegrationTest {

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

    @MockitoSpyBean ProcessCancelledStockService processService;
    @MockitoSpyBean KafkaTemplate<String, String> kafkaTemplate;

    final AtomicBoolean failHandler = new AtomicBoolean(false);
    final AtomicBoolean failRetrySend = new AtomicBoolean(false);
    final AtomicInteger executeAttempts = new AtomicInteger(0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executeAttempts.set(0);
        // 핸들러: failHandler 동안 예외 → 재시도 경로 진입.
        Mockito.doAnswer(inv -> {
            executeAttempts.incrementAndGet();
            if (failHandler.get()) {
                throw new RuntimeException("주입된 일시 장애 — 재고 복원 실패");
            }
            return inv.callRealMethod();
        }).when(processService).execute(any(Command.class));

        // retry 토픽 send: failRetrySend 동안 실패 future(→ publishToRetry .get(5s) 예외 전파). 그 외 실제 위임.
        Mockito.doAnswer(inv -> {
            ProducerRecord<String, String> rec = inv.getArgument(0);
            if (failRetrySend.get() && "payment.cancelled.retry".equals(rec.topic())) {
                return CompletableFuture.failedFuture(new RuntimeException("주입된 retry send 브로커 장애"));
            }
            return inv.callRealMethod();
        }).when(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("LOSS-01: retry send 실패 구간엔 원본 미커밋(재전달), 회복 후 재고 복원 — 손실0")
    void retrySendFailureCausesRedeliveryNoLoss() throws Exception {
        // 1. seed 재고 5 → reserve 3 → available 2
        jdbc.update("INSERT INTO product(name, category_id) VALUES ('티셔츠', ?)", CategoryFixtures.leafId(jdbc));
        Long productId = jdbc.queryForObject("SELECT id FROM product ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_sku(product_id, sku_code, option_summary) VALUES (?, 'TS-LOSS-1', 'M/Black')",
                productId);
        long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = 'TS-LOSS-1'", Long.class);
        jdbc.update("INSERT INTO product_stock(sku_id, available_qty) VALUES (?, 5)", skuId);

        String paymentKey = "PAY-LOSS-1";
        String cancelRequestId = "9101";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(skuId)).isEqualTo(2);

        // 2. 결함 ON: 핸들러 실패 + retry send 실패 → 원본이 계속 재전달(미ack)
        failHandler.set(true);
        failRetrySend.set(true);
        String json = ("""
                {"cancelRequestId":%s,"paymentKey":"%s","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":10,"itemAmount":30000,"skuId":%d,"quantity":3}],\
                "cancelledAt":"2026-08-01T00:00:00Z"}""").formatted(cancelRequestId, paymentKey, skuId);
        publish("payment.cancelled", cancelRequestId, json);

        // 3. 원본 재전달 증명: execute 가 2회 이상 호출됨(커밋됐다면 1회로 끝났을 것)
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(executeAttempts.get()).isGreaterThanOrEqualTo(2));
        // 아직 복원 안 됨(손실도 아님 — 재전달 중)
        assertThat(availableQty(skuId)).isEqualTo(2);
        assertThat(dlqCount(cancelRequestId)).isZero();

        // 4. 회복: 결함 제거 → 재전달된 원본이 정상 처리되어 재고 복원(손실 0)
        failHandler.set(false);
        failRetrySend.set(false);
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(availableQty(skuId)).isEqualTo(5));
    }

    private int dlqCount(String cancelRequestId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cancel_restore_dlq WHERE cancel_request_id = ?",
                Integer.class, cancelRequestId);
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
