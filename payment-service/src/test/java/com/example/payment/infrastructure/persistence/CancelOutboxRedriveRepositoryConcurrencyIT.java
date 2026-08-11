package com.example.payment.infrastructure.persistence;

import com.example.payment.application.exception.ActiveRedriveExistsException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CancelOutboxRedriveRepositoryConcurrencyIT extends AbstractRepositoryTest {

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
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requestedRedriveIsNotVisibleAfterCallerTransactionRollsBack() {
        long outboxId = seedDeadOutbox(9_300_002L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

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
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
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

    private long seedDeadOutbox(long cancelRequestId) {
        jdbc.update("""
            INSERT INTO cancel_event_outbox (cancel_request_id, payload, status)
            VALUES (?, JSON_OBJECT('cancelRequestId', ?), 'DEAD')
            """, cancelRequestId, cancelRequestId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private record Result(boolean created, RuntimeException error) {}
}
