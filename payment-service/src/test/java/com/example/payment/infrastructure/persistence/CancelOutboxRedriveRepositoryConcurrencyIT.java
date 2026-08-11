package com.example.payment.infrastructure.persistence;

import com.example.payment.application.exception.ActiveRedriveExistsException;
import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.service.CancelOutboxRedriveAuditJson;
import com.example.payment.application.service.CancelOutboxRedriveTelemetry;
import com.example.payment.application.service.CancelOutboxRedriveWorker;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import com.example.payment.domain.entity.PaymentStatus;
import com.example.payment.infrastructure.config.CancelOutboxRedriveExecutorConfig;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveDispatcher;
import com.example.payment.infrastructure.scheduler.CancelOutboxRedriveTaskExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveRepositoryConcurrencyIT extends AbstractRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-11T05:10:00Z");

    @Autowired
    private CancelOutboxRedriveRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRequestsCreateExactlyOneActiveRedrive() throws Exception {
        long outboxId = seedDeadOutbox(9_300_001L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Result>> futures = List.of(
                executor.submit(() -> createAfterStart(transaction, start, outboxId)),
                executor.submit(() -> createAfterStart(transaction, start, outboxId)));
            start.countDown();

            List<Result> results = List.of(
                futures.get(0).get(30, TimeUnit.SECONDS),
                futures.get(1).get(30, TimeUnit.SECONDS));

            assertThat(results).filteredOn(Result::created).hasSize(1);
            assertThat(results).filteredOn(r -> r.error() instanceof ActiveRedriveExistsException)
                .hasSize(1);
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cancel_outbox_redrive
                 WHERE source_outbox_id = ? AND status IN ('REQUESTED', 'REDRIVING')
                """, Integer.class, outboxId)).isEqualTo(1);
        } finally {
            try {
                executor.shutdownNow();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } finally {
                deleteFixture(outboxId);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requestedRedriveIsNotVisibleAfterCallerTransactionRollsBack() {
        long outboxId = seedDeadOutbox(9_300_002L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try {
            transaction.executeWithoutResult(status -> {
                repository.createRequested(
                    outboxId, "operator-1", "rollback", Instant.parse("2026-08-11T05:01:00Z"));
                status.setRollbackOnly();
            });

            Integer persistedRows = transaction.execute(status -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM cancel_outbox_redrive
                 WHERE source_outbox_id = ?
                """, Integer.class, outboxId));

            assertThat(persistedRows).isZero();
        } finally {
            deleteFixture(outboxId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTryStartCallsHaveExactlyOneCasWinner() throws Exception {
        long outboxId = seedDeadOutbox(9_300_003L);
        long redriveId = repository.createRequested(
            outboxId, "operator-1", "claim", Instant.parse("2026-08-11T05:02:00Z")).getId();
        Instant startedAt = Instant.parse("2026-08-11T05:02:03.123456Z");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures = List.of(
                executor.submit(() -> tryStartAfterSignal(transaction, start, redriveId, startedAt)),
                executor.submit(() -> tryStartAfterSignal(transaction, start, redriveId, startedAt)));
            start.countDown();

            List<Boolean> results = List.of(
                futures.get(0).get(30, TimeUnit.SECONDS),
                futures.get(1).get(30, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);
            var loaded = repository.findById(redriveId).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
            assertThat(loaded.getStartedAt()).isEqualTo(startedAt);
        } finally {
            try {
                executor.shutdownNow();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } finally {
                deleteFixture(outboxId);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoWorkerInstancesAllowExactlyOneReplayAfterCasClaim() throws Exception {
        long cancelRequestId = 9_300_004L;
        long outboxId = seedDeadOutbox(cancelRequestId);
        long redriveId = repository.createRequested(
            outboxId, "operator-1", "two workers", NOW.minusSeconds(1)).getId();
        CancelOutboxInspectionUseCase inspection = mock(CancelOutboxInspectionUseCase.class);
        CancelOutboxSourcePort sourcePort = mock(CancelOutboxSourcePort.class);
        CancelOutboxRedriveTelemetry telemetry = mock(CancelOutboxRedriveTelemetry.class);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch replayEntered = new CountDownLatch(1);
        CountDownLatch releaseReplay = new CountDownLatch(1);
        AtomicInteger replayCalls = new AtomicInteger();
        CancelEventReplayPort replayPort = (requestId, payload) -> {
            replayCalls.incrementAndGet();
            replayEntered.countDown();
            awaitLatch(releaseReplay, "release first claimed replay");
            return new CancelEventReplayPort.ReplayResult("payment.cancelled", 0, 17L);
        };
        stubWorkerInputs(inspection, sourcePort, outboxId, cancelRequestId);
        CancelOutboxRedriveWorker firstWorker = worker(
            inspection, sourcePort, replayPort, telemetry);
        CancelOutboxRedriveWorker secondWorker = worker(
            inspection, sourcePort, replayPort, telemetry);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            List<Future<Void>> futures = List.of(
                workers.submit(startWorker(start, firstWorker, redriveId)),
                workers.submit(startWorker(start, secondWorker, redriveId)));
            start.countDown();

            assertThat(replayEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitCondition(
                () -> futures.stream().filter(Future::isDone).count() == 1,
                Duration.ofSeconds(10))).isTrue();
            assertThat(replayCalls).hasValue(1);
            assertThat(repository.findById(redriveId).orElseThrow().getStatus())
                .isEqualTo(CancelOutboxRedriveStatus.REDRIVING);

            releaseReplay.countDown();
            futures.get(0).get(10, TimeUnit.SECONDS);
            futures.get(1).get(10, TimeUnit.SECONDS);

            verify(inspection, times(1)).inspect(outboxId);
            verify(sourcePort, times(1)).findById(outboxId);
            var claimed = repository.findById(redriveId).orElseThrow();
            assertThat(claimed.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REDRIVING);
            assertThat(claimed.getResult()).contains("\"offset\": 17");
        } finally {
            releaseReplay.countDown();
            try {
                workers.shutdownNow();
                workers.awaitTermination(30, TimeUnit.SECONDS);
            } finally {
                deleteFixture(outboxId);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void realMaxFiveExecutorRejectsSixthWithoutQueueThenStartsItOnNextPoll() throws Exception {
        List<Long> outboxIds = new ArrayList<>();
        List<Long> redriveIds = new ArrayList<>();
        for (long cancelRequestId = 9_300_010L; cancelRequestId < 9_300_016L; cancelRequestId++) {
            long outboxId = seedDeadOutbox(cancelRequestId);
            outboxIds.add(outboxId);
            redriveIds.add(repository.createRequested(
                outboxId, "operator-capacity", "executor saturation", NOW).getId());
        }
        CancelOutboxInspectionUseCase inspection = mock(CancelOutboxInspectionUseCase.class);
        CancelOutboxSourcePort sourcePort = mock(CancelOutboxSourcePort.class);
        for (int index = 0; index < outboxIds.size(); index++) {
            stubWorkerInputs(
                inspection,
                sourcePort,
                outboxIds.get(index),
                9_300_010L + index);
        }
        CountDownLatch firstFiveEnteredReplay = new CountDownLatch(5);
        CountDownLatch releaseFirstFive = new CountDownLatch(1);
        CountDownLatch sixthEnteredReplay = new CountDownLatch(1);
        AtomicInteger replayCalls = new AtomicInteger();
        CancelEventReplayPort replayPort = (requestId, payload) -> {
            int call = replayCalls.incrementAndGet();
            if (call <= 5) {
                firstFiveEnteredReplay.countDown();
                awaitLatch(releaseFirstFive, "release saturated workers");
            } else {
                sixthEnteredReplay.countDown();
            }
            return new CancelEventReplayPort.ReplayResult("payment.cancelled", 0, call);
        };
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor springExecutor =
            new CancelOutboxRedriveExecutorConfig().cancelRedriveExecutor(5, 10);
        springExecutor.initialize();
        CancelOutboxRedriveTelemetry telemetry =
            new CancelOutboxRedriveTelemetry(meterRegistry, springExecutor);
        CancelOutboxRedriveTaskExecutor taskExecutor =
            new CancelOutboxRedriveTaskExecutor(springExecutor, telemetry);
        CancelOutboxRedriveWorker worker = worker(
            inspection, sourcePort, replayPort, telemetry);
        CancelOutboxRedriveDispatcher dispatcher =
            new CancelOutboxRedriveDispatcher(repository, worker, taskExecutor, 100);

        try {
            dispatcher.dispatch();

            assertThat(firstFiveEnteredReplay.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitCondition(
                () -> statusCount(redriveIds, CancelOutboxRedriveStatus.REDRIVING) == 5,
                Duration.ofSeconds(10))).isTrue();
            assertThat(statusCount(redriveIds, CancelOutboxRedriveStatus.REDRIVING)).isEqualTo(5);
            assertThat(statusCount(redriveIds, CancelOutboxRedriveStatus.REQUESTED)).isEqualTo(1);
            assertThat(meterRegistry.get("payment.cancel.redrive.executor.active")
                .gauge().value()).isEqualTo(5.0);
            assertThat(meterRegistry.get("payment.cancel.redrive.executor.rejected.total")
                .counter().count()).isEqualTo(1.0);

            releaseFirstFive.countDown();
            assertThat(awaitCondition(
                () -> taskExecutor.activeCount() == 0,
                Duration.ofSeconds(10))).isTrue();
            dispatcher.dispatch();

            assertThat(sixthEnteredReplay.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitCondition(
                () -> statusCount(redriveIds, CancelOutboxRedriveStatus.REQUESTED) == 0,
                Duration.ofSeconds(10))).isTrue();
            assertThat(replayCalls).hasValue(6);
            assertThat(statusCount(redriveIds, CancelOutboxRedriveStatus.REDRIVING)).isEqualTo(6);
            assertThat(meterRegistry.get("payment.cancel.redrive.executor.rejected.total")
                .counter().count()).isEqualTo(1.0);
        } finally {
            releaseFirstFive.countDown();
            try {
                springExecutor.shutdown();
                meterRegistry.close();
            } finally {
                deleteFixtures(outboxIds);
            }
        }
    }

    private Result createAfterStart(TransactionTemplate transaction, CountDownLatch start, long outboxId)
        throws InterruptedException {
        start.await(30, TimeUnit.SECONDS);
        try {
            transaction.execute(status -> repository.createRequested(
                outboxId, "operator-1", "concurrency", Instant.parse("2026-08-11T05:00:00Z")));
            return new Result(true, null);
        } catch (RuntimeException error) {
            return new Result(false, error);
        }
    }

    private boolean tryStartAfterSignal(
        TransactionTemplate transaction, CountDownLatch start, long redriveId, Instant startedAt
    ) throws InterruptedException {
        start.await(30, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(transaction.execute(
            status -> repository.tryStart(redriveId, startedAt)));
    }

    private Callable<Void> startWorker(
        CountDownLatch start,
        CancelOutboxRedriveWorker worker,
        long redriveId
    ) {
        return () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            worker.start(redriveId);
            return null;
        };
    }

    private CancelOutboxRedriveWorker worker(
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxSourcePort sourcePort,
        CancelEventReplayPort replayPort,
        CancelOutboxRedriveTelemetry telemetry
    ) {
        return new CancelOutboxRedriveWorker(
            repository,
            inspection,
            sourcePort,
            replayPort,
            new CancelOutboxRedriveAuditJson(new ObjectMapper()),
            telemetry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubWorkerInputs(
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxSourcePort sourcePort,
        long outboxId,
        long cancelRequestId
    ) {
        when(inspection.inspect(outboxId)).thenReturn(new CancelOutboxInspectionUseCase.Result(
            outboxId,
            cancelRequestId,
            CancelOutboxDecision.REDRIVE_REQUIRED,
            null,
            snapshot(CancelRestoreLegStatus.NOT_APPLIED, 11L),
            snapshot(CancelRestoreLegStatus.APPLIED, 21L)));
        when(sourcePort.findById(outboxId)).thenReturn(Optional.of(
            new CancelOutboxSourcePort.SourceSnapshot(
                outboxId,
                cancelRequestId,
                "{\"cancelRequestId\":" + cancelRequestId + "}",
                "DEAD",
                CancelStatus.COMPLETED,
                PaymentStatus.CANCELLED)));
    }

    private CancelRestoreLegSnapshot snapshot(CancelRestoreLegStatus status, long targetId) {
        return new CancelRestoreLegSnapshot(status, List.of(
            new CancelRestoreLegSnapshot.Evidence(targetId, status.name(), 2, 2)));
    }

    private int statusCount(List<Long> redriveIds, CancelOutboxRedriveStatus status) {
        return (int) redriveIds.stream()
            .map(repository::findById)
            .flatMap(Optional::stream)
            .filter(redrive -> redrive.getStatus() == status)
            .count();
    }

    private boolean awaitCondition(BooleanSupplier condition, Duration timeout)
        throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting to " + description, exception);
        }
    }

    private long seedDeadOutbox(long cancelRequestId) {
        jdbc.update("""
            INSERT INTO cancel_event_outbox (cancel_request_id, payload, status)
            VALUES (?, JSON_OBJECT('cancelRequestId', ?), 'DEAD')
            """, cancelRequestId, cancelRequestId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void deleteFixture(long outboxId) {
        jdbc.update("DELETE FROM cancel_outbox_redrive WHERE source_outbox_id = ?", outboxId);
        jdbc.update("DELETE FROM cancel_event_outbox WHERE id = ?", outboxId);
    }

    private void deleteFixtures(List<Long> outboxIds) {
        for (long outboxId : outboxIds.reversed()) {
            jdbc.update("DELETE FROM cancel_outbox_redrive WHERE source_outbox_id = ?", outboxId);
        }
        for (long outboxId : outboxIds.reversed()) {
            jdbc.update("DELETE FROM cancel_event_outbox WHERE id = ?", outboxId);
        }
    }

    private record Result(boolean created, RuntimeException error) {}
}
