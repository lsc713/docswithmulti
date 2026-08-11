package com.example.payment.integration;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveConvergencePoller;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveDispatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
    "cancel.publish.mode=OUTBOX",
    "kafka.topic.payment-cancelled=payment.cancelled.redrive-it",
    "cancel.redrive.dispatch-ms=86400000",
    "cancel.redrive.convergence-ms=86400000",
    "cancel.outbox.poll-ms=86400000",
    "cancel.outbox.purge-ms=86400000",
    "payment.completed.outbox.poll-ms=86400000"
})
class CancelOutboxRedriveWorkerIT {

    private static final String TOPIC = "payment.cancelled.redrive-it";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("payment_redrive_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @MockitoBean OrderCancelStatusPort orderStatusPort;
    @MockitoBean StockRestoreStatusPort stockStatusPort;
    @MockitoBean PgCancelPort pgCancelPort;
    @MockitoBean RiskManagementPort riskManagementPort;
    @MockitoBean(answers = Answers.RETURNS_MOCKS) RedissonClient redissonClient;

    @Autowired JdbcTemplate jdbc;
    @Autowired CancelOutboxRedriveRepository repository;
    @Autowired CancelOutboxRedriveDispatcher dispatcher;
    @Autowired CancelOutboxRedriveConvergencePoller convergencePoller;
    @Autowired ObjectMapper objectMapper;

    private static KafkaConsumer<String, String> consumer;

    @BeforeAll
    static void createTopicAndConsumer() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                .all().get();
        }

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "cancel-redrive-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(TOPIC));
        awaitAssignment();
    }

    @BeforeEach
    void resetDoublesAndDrainKafka() {
        reset(orderStatusPort, stockStatusPort);
        drainKafka();
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM cancel_outbox_redrive");
        jdbc.update("DELETE FROM cancel_event_outbox");
        jdbc.update("DELETE FROM cancel_request");
        jdbc.update("DELETE FROM payment");
    }

    @AfterAll
    static void closeConsumer() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void dispatchPublishesExactStoredEventPersistsAckAndConvergesWithoutReplay() throws Exception {
        var source = seedDeadSource(101L, validPayload(101L));
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);
        var requested = repository.createRequested(source.outboxId(), "operator-1", "recover", Instant.now());

        dispatcher.dispatch();

        ConsumerRecord<String, String> record = consumeOne();
        assertThat(record.key()).isEqualTo("101");
        assertThat(record.value()).isEqualTo(source.storedPayload());

        var redriving = repository.findById(requested.getId()).orElseThrow();
        assertThat(redriving.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        assertThat(objectMapper.readTree(redriving.getBeforeState()).path("decision").asString())
            .isEqualTo("REDRIVE_REQUIRED");
        assertThat(redriving.getResult()).isNotNull();
        var ack = objectMapper.readTree(redriving.getResult());
        assertThat(ack.path("topic").asString()).isEqualTo(TOPIC);
        assertThat(ack.path("partition").asInt()).isEqualTo(record.partition());
        assertThat(ack.path("offset").asLong()).isEqualTo(record.offset());
        assertThat(jdbc.queryForObject(
            "SELECT status FROM cancel_event_outbox WHERE id = ?", String.class, source.outboxId()))
            .isEqualTo("DEAD");
        assertThat(jdbc.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM cancel_event_outbox WHERE id = ?",
            String.class, source.outboxId())).isEqualTo(source.storedPayload());

        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        convergencePoller.poll();

        var resolved = repository.findById(requested.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(CancelOutboxRedriveStatus.RESOLVED);
        assertThat(objectMapper.readTree(resolved.getAfterState()).path("decision").asString())
            .isEqualTo("ALREADY_APPLIED");
        var terminalState = List.of(
            resolved.getStatus().name(), resolved.getResult(), resolved.getAfterState(),
            resolved.getCompletedAt().toString());

        convergencePoller.poll();

        var afterSecondPoll = repository.findById(requested.getId()).orElseThrow();
        assertThat(List.of(
            afterSecondPoll.getStatus().name(), afterSecondPoll.getResult(),
            afterSecondPoll.getAfterState(), afterSecondPoll.getCompletedAt().toString()))
            .isEqualTo(terminalState);
        assertThat(consumer.poll(Duration.ofMillis(700))).isEmpty();
    }

    @Test
    void alreadyAppliedTerminatesWithoutPublishing() throws Exception {
        var source = seedDeadSource(201L, validPayload(201L));
        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        var requested = repository.createRequested(source.outboxId(), "operator-2", "confirm", Instant.now());

        dispatcher.dispatch();

        var terminal = repository.findById(requested.getId()).orElseThrow();
        assertThat(terminal.getStatus())
            .isEqualTo(CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED);
        assertThat(objectMapper.readTree(terminal.getResult()).path("outcome").asString())
            .isEqualTo("ALREADY_APPLIED");
        assertThat(consumer.poll(Duration.ofMillis(700))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsafeSourceIsRejectedWithoutPublishing(boolean invalidPayload) {
        long cancelRequestId = invalidPayload ? 301L : 302L;
        String payload = invalidPayload ? "{\"cancelRequestId\":301}" : validPayload(cancelRequestId);
        var source = seedDeadSource(cancelRequestId, payload);
        if (!invalidPayload) {
            stubInspection(CancelRestoreLegStatus.INCONSISTENT, CancelRestoreLegStatus.APPLIED);
        }
        var requested = repository.createRequested(source.outboxId(), "operator-3", "verify", Instant.now());

        dispatcher.dispatch();

        var terminal = repository.findById(requested.getId()).orElseThrow();
        assertThat(terminal.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REJECTED);
        assertThat(terminal.getLastError()).isEqualTo(
            invalidPayload ? "INVALID_PAYLOAD" : "INCONSISTENT_DOWNSTREAM_STATE");
        assertThat(consumer.poll(Duration.ofMillis(700))).isEmpty();
    }

    private SourceFixture seedDeadSource(long cancelRequestId, String payload) {
        long paymentId = cancelRequestId + 10_000L;
        long outboxId = cancelRequestId + 20_000L;
        jdbc.update("""
            INSERT INTO payment
                (id, payment_key, merchant_id, user_id, pg_type, total_amount,
                 currency, status, order_id)
            VALUES (?, ?, 1, 7, 'CARD', 1000, 'KRW', 'CANCELLED', 100)
            """, paymentId, "pay-redrive-" + cancelRequestId);
        jdbc.update("""
            INSERT INTO cancel_request
                (id, payment_id, request_hash, cancel_amount, cancel_reason, status,
                 completed_at, cancel_item_ids)
            VALUES (?, ?, ?, 1000, 'fixture', 'COMPLETED', CURRENT_TIMESTAMP(3), JSON_ARRAY(1))
            """, cancelRequestId, paymentId, "hash-" + cancelRequestId);
        jdbc.update("""
            INSERT INTO cancel_event_outbox
                (id, cancel_request_id, payload, status, retry_count, last_error)
            VALUES (?, ?, CAST(? AS JSON), 'DEAD', 10, 'broker unavailable')
            """, outboxId, cancelRequestId, payload);
        String storedPayload = jdbc.queryForObject(
            "SELECT CAST(payload AS CHAR) FROM cancel_event_outbox WHERE id = ?",
            String.class, outboxId);
        return new SourceFixture(outboxId, storedPayload);
    }

    private String validPayload(long cancelRequestId) {
        return "{\"cancelRequestId\":" + cancelRequestId
            + ",\"paymentKey\":\"pay-redrive-" + cancelRequestId
            + "\",\"cancelledItems\":[{\"orderItemId\":11,\"skuId\":21,\"quantity\":2}]}";
    }

    private void stubInspection(
        CancelRestoreLegStatus orderStatus,
        CancelRestoreLegStatus stockStatus
    ) {
        when(orderStatusPort.inspect(org.mockito.ArgumentMatchers.any()))
            .thenReturn(snapshot(orderStatus, 11L));
        when(stockStatusPort.inspect(org.mockito.ArgumentMatchers.any()))
            .thenReturn(snapshot(stockStatus, 21L));
    }

    private CancelRestoreLegSnapshot snapshot(CancelRestoreLegStatus status, long targetId) {
        return new CancelRestoreLegSnapshot(status, List.of(
            new CancelRestoreLegSnapshot.Evidence(targetId, status.name(), 2, 2)));
    }

    private ConsumerRecord<String, String> consumeOne() {
        var records = consumer.poll(Duration.ofSeconds(10));
        assertThat(records.count()).isEqualTo(1);
        return records.iterator().next();
    }

    private static void awaitAssignment() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).isNotEmpty();
    }

    private void drainKafka() {
        while (!consumer.poll(Duration.ofMillis(100)).isEmpty()) {
            // Drain records from the preceding scenario before asserting no-publish behavior.
        }
    }

    private record SourceFixture(long outboxId, String storedPayload) {}
}
