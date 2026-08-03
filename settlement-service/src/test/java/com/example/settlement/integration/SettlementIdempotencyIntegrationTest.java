package com.example.settlement.integration;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 정산 멱등 이중 가드 (CANCEL-01): 같은 payment.cancelled(동일 cancelRequestId) 2회 →
 * settlement_line 1건, cancel_amount 1회만 증분. processed_settlement_event 선체크(fast path) +
 * settlement_line.event_id UK(권위 가드) — 재전달은 no-op ack.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Settlement idempotency (동일 이벤트 2회 → 라인 1건, 증분 1회)")
class SettlementIdempotencyIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("settlement_db")
            .withUsername("settlement")
            .withPassword("settlement");

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

    @Test
    @DisplayName("동일 payment.cancelled 2회 발행 → 라인 1건 + cancel_amount 25000 (1회만 카운트)")
    void duplicateEventYieldsSingleLineAndSingleIncrement() throws Exception {
        String eventId = "cancel:7777";
        String json = """
                {"cancelRequestId":7777,"paymentKey":"PAY-DUP-1","merchantId":88,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":10,"itemAmount":25000,"skuId":9,"quantity":1}],\
                "cancelledAt":"2026-07-31T00:00:00.123Z"}""";

        // 1) 최초 발행 → 라인 1건 + cancel_amount 25000 안정화까지 대기
        publish("payment.cancelled", "7777", json);
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(lineCount(eventId)).isEqualTo(1);
                    assertThat(cancelAmount(88L)).isEqualByComparingTo("25000");
                });

        // 2) 동일 이벤트 재발행(중복) → no-op 이어야 함
        publish("payment.cancelled", "7777", json);

        // 3) 재발행 소비를 위한 시간을 준 뒤 3초간 상태가 불변인지 검증(라인 1건, 증분 1회)
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500);
            assertThat(lineCount(eventId)).as("중복 이벤트가 라인을 늘리면 안 됨").isEqualTo(1);
            assertThat(cancelAmount(88L)).as("중복 이벤트가 cancel_amount 를 이중 증분하면 안 됨")
                    .isEqualByComparingTo("25000");
        }
    }

    private int lineCount(String eventId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_line WHERE event_id = ?", Integer.class, eventId);
        return c == null ? 0 : c;
    }

    private BigDecimal cancelAmount(long merchantId) {
        return jdbc.queryForObject(
                "SELECT cancel_amount FROM settlement WHERE merchant_id = ?", BigDecimal.class, merchantId);
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
