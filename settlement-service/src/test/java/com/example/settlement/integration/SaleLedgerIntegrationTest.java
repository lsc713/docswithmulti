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
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SALE 적재 tracer e2e (SALE-02): payment.completed 1건 발행 → settlement consumer 가 SALE 라인 1건 적재
 * + 헤더 upsert(OPEN) + gross_amount 증분.
 *
 * <p>KST 주 귀속: completedAt=2026-07-31T00:00:00.123Z(= 09:00 KST 금요일) → 정산주 월요일 2026-07-27.
 * 실 MySQL + 실 Kafka(Testcontainers). Docker 필요.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Settlement SALE tracer (payment.completed → SALE 라인 + 헤더 gross 증분)")
class SaleLedgerIntegrationTest {

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
    @DisplayName("SALE-02: payment.completed → SALE 라인 1건 + OPEN 헤더 gross_amount=totalAmount")
    void saleEventRecordsLedgerLineAndGross() throws Exception {
        long merchantId = 77L;
        // totalAmount = 50000 (= Σ items[].itemAmount = 30000 + 20000)
        String json = """
                {"paymentKey":"PAY-SALE-1","merchantId":77,"totalAmount":50000,\
                "items":[\
                {"paymentItemId":1,"itemAmount":30000},\
                {"paymentItemId":2,"itemAmount":20000}],\
                "completedAt":"2026-07-31T00:00:00.123Z"}""";
        publish("payment.completed", "PAY-SALE-1", json);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(lineCount("sale:PAY-SALE-1")).isEqualTo(1));

        Long settlementId = jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = ?", Long.class, merchantId);
        assertThat(settlementId).isNotNull();

        LocalDate periodStart = jdbc.queryForObject(
                "SELECT period_start FROM settlement WHERE id = ?", Date.class, settlementId).toLocalDate();
        assertThat(periodStart).isEqualTo(LocalDate.of(2026, 7, 27));

        String status = jdbc.queryForObject(
                "SELECT status FROM settlement WHERE id = ?", String.class, settlementId);
        assertThat(status).isEqualTo("OPEN");

        BigDecimal gross = jdbc.queryForObject(
                "SELECT gross_amount FROM settlement WHERE id = ?", BigDecimal.class, settlementId);
        assertThat(gross).isEqualByComparingTo("50000");

        String type = jdbc.queryForObject(
                "SELECT type FROM settlement_line WHERE event_id = ?", String.class, "sale:PAY-SALE-1");
        assertThat(type).isEqualTo("SALE");
    }

    @Test
    @DisplayName("SALE-02 멱등: 같은 payment.completed 2회 → SALE 라인 1건, gross 1회만 증분")
    void duplicateEvent_countedOnce() throws Exception {
        long merchantId = 91L;
        String dupJson = """
                {"paymentKey":"PAY-DUP-1","merchantId":91,"totalAmount":40000,\
                "items":[{"paymentItemId":1,"itemAmount":40000}],\
                "completedAt":"2026-07-31T00:00:00.123Z"}""";
        // 같은 키 → 단일 파티션 순서 보장. 2회 발행(중복) 후 마커 이벤트로 '둘 다 소비됨'을 관측 가능하게 확정.
        publish("payment.completed", "PAY-DUP-1", dupJson);
        publish("payment.completed", "PAY-DUP-1", dupJson);
        String markerJson = """
                {"paymentKey":"PAY-DUP-MARKER","merchantId":91,"totalAmount":10000,\
                "items":[{"paymentItemId":9,"itemAmount":10000}],\
                "completedAt":"2026-07-31T00:00:00.123Z"}""";
        publish("payment.completed", "PAY-DUP-MARKER", markerJson);

        // 마커 라인이 보이면 앞선 중복 2건은 이미 (순서대로) 소비 완료.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(lineCount("sale:PAY-DUP-MARKER")).isEqualTo(1));

        assertThat(lineCount("sale:PAY-DUP-1")).isEqualTo(1); // 중복 적재 없음
        // gross = 40000(1회만) + 10000(마커) = 50000 → 이중 증분이면 90000이 됐을 것.
        BigDecimal gross = jdbc.queryForObject(
                "SELECT gross_amount FROM settlement WHERE merchant_id = ?", BigDecimal.class, merchantId);
        assertThat(gross).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("SALE-02 주경계: 2026-08-02T15:00:00Z(= Mon 08-03 00:00 KST) → period_start 2026-08-03(다음 KST 주)")
    void fifteenHundredZ_bucketsIntoNextKstWeek() throws Exception {
        String json = """
                {"paymentKey":"PAY-WEEK-1","merchantId":92,"totalAmount":15000,\
                "items":[{"paymentItemId":1,"itemAmount":15000}],\
                "completedAt":"2026-08-02T15:00:00Z"}""";
        publish("payment.completed", "PAY-WEEK-1", json);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(lineCount("sale:PAY-WEEK-1")).isEqualTo(1));

        LocalDate periodStart = jdbc.queryForObject(
                "SELECT period_start FROM settlement WHERE merchant_id = 92", Date.class).toLocalDate();
        assertThat(periodStart).isEqualTo(LocalDate.of(2026, 8, 3)); // 이전 주(07-27) 아님 — Z→KST 변환 후 날짜
    }

    private int lineCount(String eventId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_line WHERE event_id = ?", Integer.class, eventId);
        return c == null ? 0 : c;
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
