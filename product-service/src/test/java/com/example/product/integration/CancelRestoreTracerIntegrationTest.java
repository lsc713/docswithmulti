package com.example.product.integration;

import com.example.product.application.service.StockService;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 취소 복원 tracer e2e (RST-02): seed(재고5) → reserve3(available=2) →
 * payment.cancelled(skuId/quantity 포함) 발행 → product consumer 가 release 로 available_qty 원복(=5).
 *
 * <p>product-service 최초 Kafka 통합테스트 — 실 MySQL + 실 Kafka(Testcontainers)로
 * payment.cancelled 발행 → 신규 consumer → StockService.release(Phase 1 원자 상태전이) 전 슬라이스 관통.
 * Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Cancel restore tracer (reserve→cancel event→stock 원복)")
class CancelRestoreTracerIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stockService;

    @Test
    @DisplayName("RST-02: reserve로 차감된 재고가 payment.cancelled 소비 후 원복된다")
    void reserveThenCancelEventRestoresStock() throws Exception {
        // 1. seed: product + SKU + 초기재고 5
        jdbc.update("INSERT INTO product(name, category_id) VALUES ('티셔츠', ?)", CategoryFixtures.leafId(jdbc));
        Long productId = jdbc.queryForObject("SELECT id FROM product ORDER BY id DESC LIMIT 1", Long.class);
        jdbc.update("INSERT INTO product_sku(product_id, sku_code, option_summary) VALUES (?, 'TS-M-BLK', 'M/Black')",
                productId);
        long skuId = jdbc.queryForObject("SELECT id FROM product_sku WHERE sku_code = 'TS-M-BLK'", Long.class);
        jdbc.update("INSERT INTO product_stock(sku_id, available_qty) VALUES (?, 5)", skuId);

        // 2. reserve qty=3 → available 5→2 (RESERVED 예약행 생성)
        String paymentKey = "PAY-CANCEL-1";
        stockService.reserve(paymentKey, List.of(new StockService.ReserveItem(skuId, 3)));
        assertThat(availableQty(skuId)).isEqualTo(2);

        // 3. payment.cancelled 발행 (cancelledItems 에 skuId/quantity 포함)
        String json = ("""
                {"cancelRequestId":9001,"paymentKey":"%s","merchantId":1,\
                "cancelledItems":[{"paymentItemId":1,"orderItemId":10,"itemAmount":30000,"skuId":%d,"quantity":3}],\
                "cancelledAt":"2026-07-31T00:00:00Z"}""").formatted(paymentKey, skuId);
        publish("payment.cancelled", "9001", json);

        // 4. consumer 가 release → available_qty 가 5로 원복
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(availableQty(skuId)).isEqualTo(5));
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
