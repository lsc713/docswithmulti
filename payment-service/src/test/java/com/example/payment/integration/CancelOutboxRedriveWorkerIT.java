package com.example.payment.integration;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.OrderCancelStatusPort;
import com.example.payment.application.interfaces.PgCancelPort;
import com.example.payment.application.interfaces.RiskManagementPort;
import com.example.payment.application.interfaces.StockRestoreStatusPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveConvergencePoller;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveDispatcher;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveRecoveryPoller;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveTaskExecutor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "cancel.publish.mode=OUTBOX",
    "kafka.topic.payment-cancelled=payment.cancelled.redrive-it",
    "cancel.redrive.dispatch-ms=86400000",
    "cancel.redrive.dispatch-initial-delay-ms=86400000",
    "cancel.redrive.convergence-ms=86400000",
    "cancel.redrive.convergence-initial-delay-ms=86400000",
    "cancel.redrive.recovery-ms=86400000",
    "cancel.redrive.recovery-initial-delay-ms=86400000",
    "cancel.redrive.publish-timeout-ms=1000",
    "cancel.outbox.poll-ms=86400000",
    "cancel.outbox.purge-ms=86400000",
    "payment.completed.outbox.poll-ms=86400000"
})
class CancelOutboxRedriveWorkerIT {

    private static final String TOPIC = "payment.cancelled.redrive-it";
    private static final Instant NOW = Instant.parse("2026-08-11T05:30:00Z");
    private static final String NOT_APPLIED_SNAPSHOT = "{\"decision\":\"REDRIVE_REQUIRED\","
        + "\"reasonCode\":null,\"order\":{\"status\":\"NOT_APPLIED\",\"evidence\":[{"
        + "\"targetId\":11,\"currentStatus\":\"NOT_APPLIED\",\"actualQuantity\":2,"
        + "\"expectedQuantity\":2}]},\"stock\":{\"status\":\"APPLIED\",\"evidence\":[{"
        + "\"targetId\":21,\"currentStatus\":\"APPLIED\",\"actualQuantity\":2,"
        + "\"expectedQuantity\":2}]}}";
    private static final String UNKNOWN_SNAPSHOT = "{\"decision\":\"UNKNOWN\","
        + "\"reasonCode\":\"DOWNSTREAM_UNKNOWN\",\"order\":{\"status\":\"UNKNOWN\","
        + "\"evidence\":[{\"targetId\":11,\"currentStatus\":\"UNKNOWN\","
        + "\"actualQuantity\":2,\"expectedQuantity\":2}]},\"stock\":{\"status\":\"APPLIED\","
        + "\"evidence\":[{\"targetId\":21,\"currentStatus\":\"APPLIED\","
        + "\"actualQuantity\":2,\"expectedQuantity\":2}]}}";
    private static final String INCONSISTENT_SNAPSHOT = "{\"decision\":\"NOT_ELIGIBLE\","
        + "\"reasonCode\":\"INCONSISTENT_DOWNSTREAM_STATE\",\"order\":{"
        + "\"status\":\"INCONSISTENT\",\"evidence\":[{\"targetId\":11,"
        + "\"currentStatus\":\"INCONSISTENT\",\"actualQuantity\":2,"
        + "\"expectedQuantity\":2}]},\"stock\":{\"status\":\"APPLIED\",\"evidence\":[{"
        + "\"targetId\":21,\"currentStatus\":\"APPLIED\",\"actualQuantity\":2,"
        + "\"expectedQuantity\":2}]}}";

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
    @MockitoBean Clock clock;
    @MockitoSpyBean KafkaTemplate<String, String> kafkaTemplate;

    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean CancelOutboxRedriveRepository repository;
    @Autowired CancelOutboxRedriveDispatcher dispatcher;
    @Autowired CancelOutboxRedriveConvergencePoller convergencePoller;
    @Autowired CancelOutboxRedriveRecoveryPoller recoveryPoller;
    @Autowired CancelOutboxRedriveTaskExecutor taskExecutor;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebApplicationContext webApplicationContext;

    private static KafkaConsumer<String, String> consumer;
    private final List<SourceFixture> sources = new ArrayList<>();
    private MockMvc mockMvc;

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
        reset(orderStatusPort, stockStatusPort, clock, kafkaTemplate, repository);
        when(clock.instant()).thenReturn(NOW);
        drainKafka();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @AfterEach
    void cleanDatabase() {
        awaitExecutorIdle();
        for (SourceFixture source : sources.reversed()) {
            jdbc.update(
                "DELETE FROM cancel_outbox_redrive WHERE source_outbox_id = ?", source.outboxId());
            jdbc.update("DELETE FROM cancel_event_outbox WHERE id = ?", source.outboxId());
            jdbc.update("DELETE FROM cancel_request WHERE id = ?", source.cancelRequestId());
            jdbc.update("DELETE FROM payment WHERE id = ?", source.paymentId());
        }
        sources.clear();
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
        SourceState sourceBefore = sourceState(source.outboxId());
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);
        var requested = repository.createRequested(source.outboxId(), "operator-1", "recover", Instant.now());
        Map<TopicPartition, Long> offsetsBeforeDispatch = endOffsets();

        dispatcher.dispatch();

        ConsumerRecord<String, String> record = consumeOne();
        Map<TopicPartition, Long> offsetsAfterDispatch = endOffsets();
        assertSingleAppend(offsetsBeforeDispatch, offsetsAfterDispatch, record);
        assertConsumerAt(offsetsAfterDispatch);
        assertThat(record.key()).isEqualTo("101");
        assertThat(record.value()).isEqualTo(source.storedPayload());

        var redriving = awaitRedrive(
            requested.getId(), redrive -> redrive.getResult() != null, "publish ACK to be saved");
        assertThat(redriving.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        assertThat(objectMapper.readTree(redriving.getBeforeState()).path("decision").asString())
            .isEqualTo("REDRIVE_REQUIRED");
        assertThat(redriving.getResult()).isNotNull();
        var ack = objectMapper.readTree(redriving.getResult());
        assertThat(ack.path("topic").asString()).isEqualTo(TOPIC);
        assertThat(ack.path("partition").asInt()).isEqualTo(record.partition());
        assertThat(ack.path("offset").asLong()).isEqualTo(record.offset());
        assertThat(sourceState(source.outboxId())).isEqualTo(sourceBefore);

        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        convergencePoller.poll();
        awaitRedrive(
            requested.getId(),
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.RESOLVED,
            "redrive to resolve");
        assertThat(endOffsets()).isEqualTo(offsetsAfterDispatch);

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
        assertThat(endOffsets()).isEqualTo(offsetsAfterDispatch);
        assertConsumerAt(offsetsAfterDispatch);
        assertThat(sourceState(source.outboxId())).isEqualTo(sourceBefore);
    }

    @Test
    void operatorHttpFlowInspectsRequestsDispatchesAndReadsResolvedJsonObjects() throws Exception {
        var source = seedDeadSource(401L, validPayload(401L));
        SourceState sourceBefore = sourceState(source.outboxId());
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);

        mockMvc.perform(get("/internal/cancel-outbox/{outboxId}", source.outboxId())
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-http"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outboxId").value(source.outboxId()))
            .andExpect(jsonPath("$.decision").value("REDRIVE_REQUIRED"))
            .andExpect(jsonPath("$.order.status").value("NOT_APPLIED"))
            .andExpect(jsonPath("$.stock.status").value("APPLIED"));

        String postBody = mockMvc.perform(post("/internal/cancel-outbox/{outboxId}/redrives", source.outboxId())
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"operator smoke\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.requestedBy").value("operator-http"))
            .andExpect(jsonPath("$.reason").value("operator smoke"))
            .andReturn().getResponse().getContentAsString();
        long redriveId = objectMapper.readTree(postBody).path("redriveId").asLong();
        assertThat(redriveId).isPositive();
        Map<TopicPartition, Long> offsetsBeforeDispatch = endOffsets();

        dispatcher.dispatch();
        ConsumerRecord<String, String> record = consumeOne();
        Map<TopicPartition, Long> offsetsAfterDispatch = endOffsets();
        assertSingleAppend(offsetsBeforeDispatch, offsetsAfterDispatch, record);
        assertConsumerAt(offsetsAfterDispatch);
        assertThat(record.key()).isEqualTo("401");
        assertThat(record.value()).isEqualTo(source.storedPayload());

        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        convergencePoller.poll();
        awaitRedrive(
            redriveId,
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.RESOLVED,
            "HTTP redrive to resolve");
        assertThat(endOffsets()).isEqualTo(offsetsAfterDispatch);

        mockMvc.perform(get("/internal/cancel-outbox/redrives/{redriveId}", redriveId)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-http"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redriveId").value(redriveId))
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.result.topic").value(TOPIC))
            .andExpect(jsonPath("$.result.partition").value(record.partition()))
            .andExpect(jsonPath("$.result.offset").value(record.offset()))
            .andExpect(jsonPath("$.beforeState.decision").value("REDRIVE_REQUIRED"))
            .andExpect(jsonPath("$.beforeState.order.status").value("NOT_APPLIED"))
            .andExpect(jsonPath("$.afterState.decision").value("ALREADY_APPLIED"))
            .andExpect(jsonPath("$.afterState.order.status").value("APPLIED"));
        assertThat(sourceState(source.outboxId())).isEqualTo(sourceBefore);
    }

    @Test
    void alreadyAppliedTerminatesWithoutPublishing() throws Exception {
        var source = seedDeadSource(201L, validPayload(201L));
        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        var requested = repository.createRequested(source.outboxId(), "operator-2", "confirm", Instant.now());
        Map<TopicPartition, Long> offsetsBeforeDispatch = endOffsets();

        dispatcher.dispatch();

        var terminal = awaitRedrive(
            requested.getId(),
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED,
            "already-applied redrive to terminate");
        assertThat(terminal.getStatus())
            .isEqualTo(CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED);
        assertThat(objectMapper.readTree(terminal.getResult()).path("outcome").asString())
            .isEqualTo("ALREADY_APPLIED");
        assertThat(endOffsets()).isEqualTo(offsetsBeforeDispatch);
        assertConsumerAt(offsetsBeforeDispatch);
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
        Map<TopicPartition, Long> offsetsBeforeDispatch = endOffsets();

        dispatcher.dispatch();

        var terminal = awaitRedrive(
            requested.getId(),
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.REJECTED,
            "unsafe redrive to reject");
        assertThat(terminal.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REJECTED);
        assertThat(terminal.getLastError()).isEqualTo(
            invalidPayload ? "INVALID_PAYLOAD" : "INCONSISTENT_DOWNSTREAM_STATE");
        assertThat(endOffsets()).isEqualTo(offsetsBeforeDispatch);
        assertConsumerAt(offsetsBeforeDispatch);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishFaults")
    void kafkaFutureFaultFailsPublishWithoutAckOrConvergenceSelection(
        String scenario,
        String expectedCode
    ) {
        long cancelRequestId = "KAFKA_TIMEOUT".equals(expectedCode) ? 501L : 502L;
        var source = seedDeadSource(cancelRequestId, validPayload(cancelRequestId));
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);
        var requested = repository.createRequested(
            source.outboxId(), "operator-fault", scenario, NOW);
        CompletableFuture<SendResult<String, String>> send = new CompletableFuture<>();
        if ("KAFKA_SEND_FAILED".equals(expectedCode)) {
            send.completeExceptionally(new IllegalStateException("broker unavailable"));
        }
        doReturn(send).when(kafkaTemplate).send(
            eq(TOPIC), eq(String.valueOf(cancelRequestId)), eq(source.storedPayload()));
        Map<TopicPartition, Long> offsetsBefore = endOffsets();

        dispatcher.dispatch();

        var failed = awaitRedrive(
            requested.getId(),
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.FAILED,
            scenario + " redrive to fail");
        assertThat(failed.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.PUBLISH);
        assertThat(failed.getLastError()).isEqualTo(expectedCode);
        assertThat(failed.getResult()).isNull();
        assertThat(repository.findConverging(NOW.minusSeconds(60), 100))
            .extracting(com.example.payment.domain.entity.CancelOutboxRedrive::getId)
            .doesNotContain(requested.getId());
        assertThat(endOffsets()).isEqualTo(offsetsBefore);
        assertConsumerAt(offsetsBefore);
    }

    @Test
    void ackBeforeStateSaveFailureKeepsHistoryAndRetriesExactImmutableEvent() throws Exception {
        var source = seedDeadSource(601L, validPayload(601L));
        SourceState sourceBefore = sourceState(source.outboxId());
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);
        var firstAttempt = repository.createRequested(
            source.outboxId(), "operator-ack-window", "first attempt", NOW);
        AtomicBoolean failFirstPublishedWrite = new AtomicBoolean(true);
        doAnswer(invocation -> failFirstPublishedWrite.getAndSet(false)
            ? false
            : invocation.callRealMethod())
            .when(repository).recordPublished(anyLong(), anyString(), anyString());
        Map<TopicPartition, Long> offsetsBeforeFirst = endOffsets();

        dispatcher.dispatch();

        ConsumerRecord<String, String> firstRecord = consumeOne();
        Map<TopicPartition, Long> offsetsAfterFirst = endOffsets();
        assertSingleAppend(offsetsBeforeFirst, offsetsAfterFirst, firstRecord);
        assertThat(firstRecord.key()).isEqualTo("601");
        assertThat(firstRecord.value()).isEqualTo(source.storedPayload());
        awaitExecutorIdle();
        var ambiguous = repository.findById(firstAttempt.getId()).orElseThrow();
        assertThat(ambiguous.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        assertThat(ambiguous.getResult()).isNull();
        assertThat(sourceState(source.outboxId())).isEqualTo(sourceBefore);

        jdbc.update(
            "UPDATE cancel_outbox_redrive SET started_at = ? WHERE id = ?",
            Timestamp.from(NOW.minusSeconds(60)), firstAttempt.getId());
        recoveryPoller.poll();
        var failed = awaitRedrive(
            firstAttempt.getId(),
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.FAILED,
            "ambiguous publish to be recovered");
        assertThat(failed.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.PUBLISH);
        assertThat(failed.getLastError()).isEqualTo("PUBLISH_STATE_UNKNOWN");
        assertThat(failed.getResult()).isNull();

        String postBody = mockMvc.perform(post(
                "/internal/cancel-outbox/{outboxId}/redrives", source.outboxId())
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-ack-window")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"inspect-before-retry confirmed\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.reason").value("inspect-before-retry confirmed"))
            .andReturn().getResponse().getContentAsString();
        long secondAttemptId = objectMapper.readTree(postBody).path("redriveId").asLong();
        assertThat(secondAttemptId).isGreaterThan(firstAttempt.getId());
        mockMvc.perform(get("/internal/cancel-outbox/redrives/{redriveId}", firstAttempt.getId())
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-ack-window"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.lastError").value("PUBLISH_STATE_UNKNOWN"));
        mockMvc.perform(get("/internal/cancel-outbox/redrives/{redriveId}", secondAttemptId)
                .header("X-User-Role", "ADMIN")
                .header("X-User-Id", "operator-ack-window"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.reason").value("inspect-before-retry confirmed"));

        Map<TopicPartition, Long> offsetsBeforeSecond = endOffsets();
        dispatcher.dispatch();
        ConsumerRecord<String, String> secondRecord = consumeOne();
        Map<TopicPartition, Long> offsetsAfterSecond = endOffsets();
        assertSingleAppend(offsetsBeforeSecond, offsetsAfterSecond, secondRecord);
        assertThat(secondRecord.key()).isEqualTo(firstRecord.key());
        assertThat(secondRecord.value()).isEqualTo(firstRecord.value());
        var acknowledged = awaitRedrive(
            secondAttemptId, redrive -> redrive.getResult() != null, "retry ACK to be saved");
        assertThat(acknowledged.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        var ack = objectMapper.readTree(acknowledged.getResult());
        assertThat(ack.path("topic").asString()).isEqualTo(TOPIC);
        assertThat(ack.path("partition").asInt()).isEqualTo(secondRecord.partition());
        assertThat(ack.path("offset").asLong()).isEqualTo(secondRecord.offset());
        verify(orderStatusPort, times(2)).inspect(org.mockito.ArgumentMatchers.any());
        verify(stockStatusPort, times(2)).inspect(org.mockito.ArgumentMatchers.any());

        stubInspection(CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED);
        convergencePoller.poll();
        awaitRedrive(
            secondAttemptId,
            redrive -> redrive.getStatus() == CancelOutboxRedriveStatus.RESOLVED,
            "retried redrive to resolve");
        verify(orderStatusPort, times(3)).inspect(org.mockito.ArgumentMatchers.any());
        verify(stockStatusPort, times(3)).inspect(org.mockito.ArgumentMatchers.any());
        assertThat(repository.findById(firstAttempt.getId()).orElseThrow().getLastError())
            .isEqualTo("PUBLISH_STATE_UNKNOWN");
        assertThat(sourceState(source.outboxId())).isEqualTo(sourceBefore);
        assertThat(endOffsets()).isEqualTo(offsetsAfterSecond);
        assertConsumerAt(offsetsAfterSecond);
    }

    @Test
    void acknowledgedRowAtFiftyNinePointNineNineNineSecondsDoesNotExpire() {
        var source = seedDeadSource(701L, validPayload(701L));
        var redrive = seedAcknowledgedRedrive(
            source, NOW.minusSeconds(60).plusMillis(1), "boundary-younger");
        stubInspection(CancelRestoreLegStatus.NOT_APPLIED, CancelRestoreLegStatus.APPLIED);

        recoveryPoller.poll();
        awaitExecutorIdle();

        var loaded = repository.findById(redrive.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
        assertThat(loaded.getResult()).isNotNull();
        assertThat(loaded.getLastError()).isNull();
    }

    @ParameterizedTest(name = "exact 60s {0} -> {1}")
    @MethodSource("deadlineCases")
    void acknowledgedRowAtExactlySixtySecondsFailsWithLiteralSnapshot(
        CancelRestoreLegStatus orderStatus,
        String expectedCode,
        String expectedSnapshot
    ) throws Exception {
        long cancelRequestId = 710L + orderStatus.ordinal();
        var source = seedDeadSource(cancelRequestId, validPayload(cancelRequestId));
        var redrive = seedAcknowledgedRedrive(
            source, NOW.minusSeconds(60), "boundary-exact");
        stubInspection(orderStatus, CancelRestoreLegStatus.APPLIED);

        recoveryPoller.poll();

        var failed = awaitRedrive(
            redrive.getId(),
            current -> current.getStatus() == CancelOutboxRedriveStatus.FAILED,
            "exact-boundary redrive to fail convergence");
        assertThat(failed.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.CONVERGENCE);
        assertThat(failed.getLastError()).isEqualTo(expectedCode);
        assertThat(objectMapper.readTree(failed.getAfterState()))
            .isEqualTo(objectMapper.readTree(expectedSnapshot));
    }

    private static Stream<Arguments> publishFaults() {
        return Stream.of(
            Arguments.of("Kafka future timeout", "KAFKA_TIMEOUT"),
            Arguments.of("Kafka future exception", "KAFKA_SEND_FAILED"));
    }

    private static Stream<Arguments> deadlineCases() {
        return Stream.of(
            Arguments.of(
                CancelRestoreLegStatus.NOT_APPLIED,
                "CONVERGENCE_TIMEOUT",
                NOT_APPLIED_SNAPSHOT),
            Arguments.of(
                CancelRestoreLegStatus.UNKNOWN,
                "DOWNSTREAM_UNKNOWN",
                UNKNOWN_SNAPSHOT),
            Arguments.of(
                CancelRestoreLegStatus.INCONSISTENT,
                "INCONSISTENT_DOWNSTREAM_STATE",
                INCONSISTENT_SNAPSHOT));
    }

    private static String inspectionJson(
        String decision,
        String reasonCode,
        String orderStatus
    ) {
        String reason = reasonCode == null ? "null" : "\"" + reasonCode + "\"";
        return "{\"decision\":\"" + decision + "\",\"reasonCode\":" + reason
            + ",\"order\":{\"status\":\"" + orderStatus
            + "\",\"evidence\":[{\"targetId\":11,\"currentStatus\":\"" + orderStatus
            + "\",\"actualQuantity\":2,\"expectedQuantity\":2}]}"
            + ",\"stock\":{\"status\":\"APPLIED\",\"evidence\":[{\"targetId\":21,"
            + "\"currentStatus\":\"APPLIED\",\"actualQuantity\":2,\"expectedQuantity\":2}]}}";
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
        SourceFixture source = new SourceFixture(outboxId, cancelRequestId, paymentId, storedPayload);
        sources.add(source);
        return source;
    }

    private String validPayload(long cancelRequestId) {
        return "{\"cancelRequestId\":" + cancelRequestId
            + ",\"paymentKey\":\"pay-redrive-" + cancelRequestId
            + "\",\"cancelledItems\":[{\"orderItemId\":11,\"skuId\":21,\"quantity\":2}]}";
    }

    private com.example.payment.domain.entity.CancelOutboxRedrive seedAcknowledgedRedrive(
        SourceFixture source,
        Instant startedAt,
        String reason
    ) {
        var requested = repository.createRequested(
            source.outboxId(), "operator-boundary", reason, startedAt.minusSeconds(1));
        assertThat(repository.tryStart(requested.getId(), startedAt)).isTrue();
        assertThat(repository.recordPublished(
            requested.getId(),
            inspectionJson("REDRIVE_REQUIRED", null, "NOT_APPLIED"),
            "{\"topic\":\"payment.cancelled.redrive-it\",\"partition\":0,\"offset\":42}"))
            .isTrue();
        return repository.findById(requested.getId()).orElseThrow();
    }

    private SourceState sourceState(long outboxId) {
        return jdbc.queryForObject("""
            SELECT id, cancel_request_id, HEX(CAST(payload AS BINARY)) AS payload_hex,
                   status, created_at, published_at, retry_count, last_error
              FROM cancel_event_outbox
             WHERE id = ?
            """, (resultSet, rowNum) -> new SourceState(
                resultSet.getLong("id"),
                resultSet.getLong("cancel_request_id"),
                resultSet.getString("payload_hex"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at"),
                resultSet.getTimestamp("published_at"),
                resultSet.getInt("retry_count"),
                resultSet.getString("last_error")), outboxId);
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

    private Map<TopicPartition, Long> endOffsets() {
        Set<TopicPartition> assignment = Set.copyOf(consumer.assignment());
        assertThat(assignment).containsExactly(new TopicPartition(TOPIC, 0));
        return Map.copyOf(consumer.endOffsets(assignment));
    }

    private void assertConsumerAt(Map<TopicPartition, Long> expectedOffsets) {
        expectedOffsets.forEach((partition, offset) ->
            assertThat(consumer.position(partition))
                .as("consumer position for %s", partition)
                .isEqualTo(offset));
    }

    private void assertSingleAppend(
        Map<TopicPartition, Long> before,
        Map<TopicPartition, Long> after,
        ConsumerRecord<String, String> record
    ) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        assertThat(record.offset()).isEqualTo(before.get(partition));
        assertThat(after.get(partition)).isEqualTo(before.get(partition) + 1L);
        assertThat(after).hasSameSizeAs(before);
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
        assertConsumerAt(endOffsets());
    }

    private com.example.payment.domain.entity.CancelOutboxRedrive awaitRedrive(
        long redriveId,
        Predicate<com.example.payment.domain.entity.CancelOutboxRedrive> condition,
        String description
    ) {
        Instant deadline = Instant.now().plusSeconds(10);
        com.example.payment.domain.entity.CancelOutboxRedrive current = null;
        while (Instant.now().isBefore(deadline)) {
            current = repository.findById(redriveId).orElse(null);
            if (current != null && condition.test(current)) {
                return current;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, exception);
            }
        }
        assertThat(current).as(description).isNotNull().matches(condition);
        return current;
    }

    private void awaitExecutorIdle() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (taskExecutor.activeCount() != 0 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for redrive executor", exception);
            }
        }
        assertThat(taskExecutor.activeCount()).as("redrive executor active count").isZero();
    }

    private record SourceFixture(
        long outboxId,
        long cancelRequestId,
        long paymentId,
        String storedPayload
    ) {}

    private record SourceState(
        long id,
        long cancelRequestId,
        String payloadHex,
        String status,
        Timestamp createdAt,
        Timestamp publishedAt,
        int retryCount,
        String lastError
    ) {}
}
