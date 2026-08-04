package com.example.settlement.integration;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 정산 취소 적재 tracer e2e (CANCEL-01/02, QUERY-01): payment.cancelled 1건 발행 →
 * settlement consumer 가 CANCEL 라인 1건 적재 + 헤더 upsert(OPEN) + cancel_amount 증분 → 조회 API 관통.
 *
 * <p>settlement-service 최초 통합테스트 — 실 MySQL + 실 Kafka(Testcontainers). Docker 필요.
 * KST 주 귀속: cancelledAt=2026-07-31T00:00:00.123Z(= 09:00 KST 금요일) → 정산주 월요일 2026-07-27.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("Settlement cancel tracer (payment.cancelled → CANCEL 라인 + 헤더 증분 + 조회)")
class SettlementCancelTracerIntegrationTest {

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
    @LocalServerPort int port;

    @Test
    @DisplayName("CANCEL-01/02: payment.cancelled → CANCEL 라인 1건 + OPEN 헤더 cancel_amount=Σitem, 조회 반환")
    void cancelEventRecordsLedgerLineAndHeader() throws Exception {
        long merchantId = 42L;
        // cancelledItems 합 = 30000 + 12000 = 42000
        String json = """
                {"cancelRequestId":9001,"paymentKey":"PAY-SET-1","merchantId":42,\
                "cancelledItems":[\
                {"paymentItemId":1,"orderItemId":10,"itemAmount":30000,"skuId":55,"quantity":3},\
                {"paymentItemId":2,"orderItemId":11,"itemAmount":12000,"skuId":56,"quantity":1}],\
                "cancelledAt":"2026-07-31T00:00:00.123Z"}""";
        publish("payment.cancelled", "9001", json);

        // consumer 가 CANCEL 라인 1건 적재할 때까지 대기
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(lineCount("cancel:9001")).isEqualTo(1));

        // 헤더: merchant 42, period_start = KST 월요일 2026-07-27, OPEN, cancel_amount = 42000
        Long settlementId = jdbc.queryForObject(
                "SELECT id FROM settlement WHERE merchant_id = ?", Long.class, merchantId);
        assertThat(settlementId).isNotNull();

        LocalDate periodStart = jdbc.queryForObject(
                "SELECT period_start FROM settlement WHERE id = ?", Date.class, settlementId).toLocalDate();
        assertThat(periodStart).isEqualTo(LocalDate.of(2026, 7, 27));

        LocalDate periodEnd = jdbc.queryForObject(
                "SELECT period_end FROM settlement WHERE id = ?", Date.class, settlementId).toLocalDate();
        assertThat(periodEnd).isEqualTo(LocalDate.of(2026, 8, 2));

        String status = jdbc.queryForObject(
                "SELECT status FROM settlement WHERE id = ?", String.class, settlementId);
        assertThat(status).isEqualTo("OPEN");

        BigDecimal cancelAmount = jdbc.queryForObject(
                "SELECT cancel_amount FROM settlement WHERE id = ?", BigDecimal.class, settlementId);
        assertThat(cancelAmount).isEqualByComparingTo("42000");

        // GET /v1/settlements/{id} → 헤더 + 라인 반환
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/settlements/" + settlementId))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"status\":\"OPEN\"");
        assertThat(resp.body()).contains("\"eventId\":\"cancel:9001\"");
        assertThat(resp.body()).contains("PAY-SET-1");

        // 리스트 조회도 관통
        HttpResponse<String> listResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/settlements?merchantId=" + merchantId))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(listResp.statusCode()).isEqualTo(200);
        assertThat(listResp.body()).contains("\"merchantId\":42");
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
