package com.example.payment.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureCode;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveTelemetryTest {

    private static final String SECRET_REASON = "operator secret reason";
    private static final String SECRET_PAYLOAD =
        "{\"paymentKey\":\"secret-pay\",\"payload\":\"secret-payload\"}";
    private static final String SECRET_PAYMENT_KEY = "secret-pay";
    private static final String SECRET_EXCEPTION = "dependency leaked exception message";

    private SimpleMeterRegistry registry;
    private ThreadPoolTaskExecutor executor;
    private CancelOutboxRedriveTelemetry telemetry;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.initialize();
        telemetry = new CancelOutboxRedriveTelemetry(registry, executor);
        logger = (Logger) LoggerFactory.getLogger(CancelOutboxRedriveTelemetry.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        executor.shutdown();
        registry.close();
    }

    @Test
    void lifecycleLogsUseStructuredBoundedFieldsWithoutSecrets() {
        telemetry.requested(redrive(CancelOutboxRedriveStatus.REQUESTED, null));
        telemetry.claimed(redrive(CancelOutboxRedriveStatus.REDRIVING, null));
        telemetry.publishAcked(
            redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            new CancelEventReplayPort.ReplayResult("payment.cancelled", 2, 91L));
        telemetry.terminal(
            redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            CancelOutboxRedriveStatus.RESOLVED,
            null,
            null);
        telemetry.terminal(
            redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            CancelOutboxRedriveStatus.REJECTED,
            null,
            CancelOutboxRedriveFailureCode.INCONSISTENT_DOWNSTREAM_STATE);
        telemetry.terminal(
            redrive(CancelOutboxRedriveStatus.REDRIVING, CancelOutboxRedriveFailureStage.PUBLISH),
            CancelOutboxRedriveStatus.FAILED,
            CancelOutboxRedriveFailureStage.PUBLISH,
            CancelOutboxRedriveFailureCode.KAFKA_TIMEOUT);
        telemetry.terminal(
            redrive(CancelOutboxRedriveStatus.REDRIVING, CancelOutboxRedriveFailureStage.CONVERGENCE),
            CancelOutboxRedriveStatus.FAILED,
            CancelOutboxRedriveFailureStage.CONVERGENCE,
            CancelOutboxRedriveFailureCode.DOWNSTREAM_UNKNOWN);
        telemetry.executorRejected();

        assertThat(appender.list).hasSize(7);
        assertEvent(0, Level.INFO, "cancel_redrive_requested", "REQUESTED");
        assertEvent(1, Level.INFO, "cancel_redrive_claimed", "REDRIVING");
        assertEvent(2, Level.INFO, "cancel_redrive_publish_acked", "REDRIVING");
        assertThat(fields(appender.list.get(2)))
            .containsEntry("topic", "payment.cancelled")
            .containsEntry("partition", "2")
            .containsEntry("offset", "91");
        assertEvent(3, Level.INFO, "cancel_redrive_resolved", "RESOLVED");
        assertEvent(4, Level.INFO, "cancel_redrive_rejected", "REJECTED");
        assertThat(fields(appender.list.get(4)))
            .containsEntry("errorCode", "INCONSISTENT_DOWNSTREAM_STATE")
            .doesNotContainKey("failureStage");
        assertEvent(5, Level.WARN, "cancel_redrive_failed", "FAILED");
        assertThat(fields(appender.list.get(5)))
            .containsEntry("failureStage", "PUBLISH")
            .containsEntry("errorCode", "KAFKA_TIMEOUT");
        assertEvent(6, Level.WARN, "cancel_redrive_failed", "FAILED");
        assertThat(fields(appender.list.get(6)))
            .containsEntry("failureStage", "CONVERGENCE")
            .containsEntry("errorCode", "DOWNSTREAM_UNKNOWN");
        String emitted = appender.list.stream()
            .map(event -> event.getFormattedMessage() + fields(event))
            .collect(Collectors.joining("\n"));
        assertThat(emitted)
            .doesNotContain(SECRET_REASON)
            .doesNotContain(SECRET_PAYLOAD)
            .doesNotContain(SECRET_PAYMENT_KEY)
            .doesNotContain(SECRET_EXCEPTION);
    }

    @Test
    void executorRejectionIncrementsCounterWithoutEmittingALogEvent() {
        telemetry.executorRejected();

        assertThat(appender.list).isEmpty();
        assertThat(registry.get("payment.cancel.redrive.executor.rejected.total")
            .counter().getId().getTags()).isEmpty();
        assertThat(registry.get("payment.cancel.redrive.executor.rejected.total")
            .counter().count()).isEqualTo(1.0);
    }

    @Test
    void terminalAndRejectedMetricsHaveOnlyBoundedContractTags() {
        telemetry.terminal(redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            CancelOutboxRedriveStatus.RESOLVED, null, null);
        telemetry.terminal(redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            CancelOutboxRedriveStatus.REJECTED, null,
            CancelOutboxRedriveFailureCode.INCONSISTENT_DOWNSTREAM_STATE);
        telemetry.terminal(redrive(CancelOutboxRedriveStatus.REDRIVING, null),
            CancelOutboxRedriveStatus.FAILED, CancelOutboxRedriveFailureStage.PUBLISH,
            CancelOutboxRedriveFailureCode.KAFKA_TIMEOUT);
        telemetry.executorRejected();

        var terminalMeters = registry.getMeters().stream()
            .filter(meter -> meter.getId().getName().equals("payment.cancel.redrive.terminal.total"))
            .toList();
        assertThat(terminalMeters).hasSize(3);
        assertThat(terminalMeters).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("failure_stage", "status"));
        assertThat(terminalMeters).allSatisfy(meter ->
            assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                if (tag.getKey().equals("status")) {
                    assertThat(tag.getValue()).isIn("RESOLVED", "REJECTED", "FAILED");
                } else {
                    assertThat(tag.getValue()).isIn("none", "PUBLISH");
                }
            }));
        assertThat(registry.get("payment.cancel.redrive.executor.rejected.total")
            .counter().getId().getTags()).isEmpty();
        assertThat(registry.get("payment.cancel.redrive.executor.rejected.total")
            .counter().count()).isEqualTo(1.0);
    }

    @Test
    void activeGaugeReadsTheRealExecutorActiveCount() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            await(release);
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.get("payment.cancel.redrive.executor.active").gauge().value())
            .isEqualTo(1.0);

        release.countDown();
    }

    @Test
    void duplicateCasLoserPathsDoNotIncrementTerminalCounters() {
        CancelOutboxRedriveRepository staleRepository =
            mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedrive active = redrive(CancelOutboxRedriveStatus.REDRIVING, null);
        when(staleRepository.failPublish(
            7L, "PUBLISH_STATE_UNKNOWN", SECRET_PAYMENT_KEY, Instant.parse("2026-08-11T00:00:00Z")))
            .thenReturn(false);
        new CancelOutboxRedriveStalePublishWorker(
            staleRepository,
            telemetry,
            Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC))
            .expire(active);

        CancelOutboxRedriveRepository deadlineRepository =
            mock(CancelOutboxRedriveRepository.class);
        CancelOutboxInspectionUseCase inspection = mock(CancelOutboxInspectionUseCase.class);
        var result = new CancelOutboxInspectionUseCase.Result(
            41L,
            77L,
            CancelOutboxDecision.REDRIVE_REQUIRED,
            null,
            new CancelRestoreLegSnapshot(CancelRestoreLegStatus.APPLIED, List.of()),
            new CancelRestoreLegSnapshot(CancelRestoreLegStatus.NOT_APPLIED, List.of()));
        when(inspection.inspect(41L)).thenReturn(result);
        when(deadlineRepository.failConvergence(
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq("CONVERGENCE_TIMEOUT"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-11T00:00:00Z"))))
            .thenReturn(false);
        new CancelOutboxRedriveDeadlineWorker(
            deadlineRepository,
            inspection,
            new CancelOutboxRedriveAuditJson(new ObjectMapper()),
            telemetry,
            Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC))
            .check(active);

        assertThat(registry.find("payment.cancel.redrive.terminal.total").meters()).isEmpty();
    }

    private void assertEvent(int index, Level level, String eventName, String status) {
        ILoggingEvent event = appender.list.get(index);
        assertThat(event.getLevel()).isEqualTo(level);
        assertThat(fields(event))
            .containsEntry("event", eventName)
            .containsEntry("redriveId", "7")
            .containsEntry("sourceOutboxId", "41")
            .containsEntry("status", status);
    }

    private Map<String, String> fields(ILoggingEvent event) {
        if (event.getKeyValuePairs() == null) {
            return Map.of();
        }
        return event.getKeyValuePairs().stream()
            .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
    }

    private CancelOutboxRedrive redrive(
        CancelOutboxRedriveStatus status,
        CancelOutboxRedriveFailureStage stage
    ) {
        return CancelOutboxRedrive.reconstitute(
            7L,
            41L,
            status,
            stage,
            "operator-1",
            SECRET_REASON,
            Instant.parse("2026-08-11T00:00:00Z"),
            Instant.parse("2026-08-11T00:00:01Z"),
            null,
            SECRET_PAYLOAD,
            SECRET_EXCEPTION,
            SECRET_PAYMENT_KEY,
            SECRET_PAYLOAD);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
