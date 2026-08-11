package com.example.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.payment.application.exception.CancelOutboxNotDeadException;
import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.RedriveAlreadyResolvedException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveService;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelOutboxRedriveRepositoryIT extends AbstractRepositoryTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private CancelOutboxRedriveRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsRequestedRedriveWithoutMutatingDeadSourceOutbox() {
        long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(9_200_101L);
        Instant requestedAt = Instant.parse("2026-08-11T01:02:03.123456Z");

        var created = repository.createRequested(
            outboxId, "operator-1", "  장애 복구  ", requestedAt);
        var loaded = repository.findById(created.getId()).orElseThrow();

        assertThat(created.getId()).isPositive();
        assertThat(loaded.getSourceOutboxId()).isEqualTo(outboxId);
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
        assertThat(loaded.getRequestedBy()).isEqualTo("operator-1");
        assertThat(loaded.getReason()).isEqualTo("  장애 복구  ");
        assertThat(loaded.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(loaded.getFailureStage()).isNull();
        assertThat(loaded.getStartedAt()).isNull();
        assertThat(loaded.getCompletedAt()).isNull();
        assertThat(loaded.getResult()).isNull();
        assertThat(loaded.getLastError()).isNull();
        assertThat(loaded.getBeforeState()).isNull();
        assertThat(loaded.getAfterState()).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM cancel_event_outbox WHERE id = ?", String.class, outboxId))
            .isEqualTo("DEAD");
    }

    @Test
    void realRequestBoundaryPersistsRequestedWorkWithoutChangingDeadSourceWithin500Milliseconds() {
        long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(9_200_150L);
        Instant requestedAt = Instant.parse("2026-08-11T01:02:03.123456Z");
        var service = new CancelOutboxRedriveService(
            repository, Clock.fixed(requestedAt, ZoneOffset.UTC));

        long startedAt = System.nanoTime();
        var created = service.request(outboxId, "operator-1", "  장애 복구  ");
        long elapsedNanos = System.nanoTime() - startedAt;

        assertThat(elapsedNanos).isLessThan(500_000_000L);
        assertThat(repository.findById(created.getId()).orElseThrow().getStatus())
            .isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM cancel_event_outbox WHERE id = ?", String.class, outboxId))
            .isEqualTo("DEAD");
    }

    @Test
    void rejectsNewRequestWhenLatestHistoryContainsResolvedStatus() {
        for (String status : new String[] {"RESOLVED", "RESOLVED_ALREADY_APPLIED"}) {
            long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(
                9_200_200L + status.length());
            insertTerminalRedrive(outboxId, status);

            assertThatThrownBy(() -> repository.createRequested(
                outboxId, "operator-1", "retry", Instant.parse("2026-08-11T02:00:00Z")))
                .isInstanceOf(RedriveAlreadyResolvedException.class);
        }
    }

    @Test
    void permitsNewRequestWhenLatestHistoryIsFailedOrRejected() {
        for (String status : new String[] {"FAILED", "REJECTED"}) {
            long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(
                9_200_300L + status.length());
            insertTerminalRedrive(outboxId, status);

            var created = repository.createRequested(
                outboxId, "operator-1", "retry", Instant.parse("2026-08-11T03:00:00Z"));

            assertThat(created.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
        }
    }

    @Test
    void rejectsUnknownOrNonDeadSourceOutbox() {
        assertThatThrownBy(() -> repository.createRequested(
            Long.MAX_VALUE, "operator-1", "retry", Instant.parse("2026-08-11T04:00:00Z")))
            .isInstanceOf(CancelOutboxNotFoundException.class);

        long outboxId = seedOutbox(9_200_401L, "PENDING");
        assertThatThrownBy(() -> repository.createRequested(
            outboxId, "operator-1", "retry", Instant.parse("2026-08-11T04:00:00Z")))
            .isInstanceOf(CancelOutboxNotDeadException.class);
    }

    @Test
    void findsOldestRequestedIdsUpToLimit() {
        retireExistingActiveRedrives();
        long first = createRequested(9_200_501L, "2026-08-11T05:00:00Z");
        long redriving = createRequested(9_200_502L, "2026-08-11T05:01:00Z");
        long second = createRequested(9_200_503L, "2026-08-11T05:02:00Z");
        long third = createRequested(9_200_504L, "2026-08-11T05:03:00Z");
        assertThat(repository.tryStart(redriving, Instant.parse("2026-08-11T05:04:00Z"))).isTrue();

        assertThat(repository.findRequestedIds(2)).containsExactly(first, second);
        assertThat(repository.findRequestedIds(10)).containsExactly(first, second, third);
    }

    @Test
    void recordsPublishedEvidenceOnlyOnceForRedrivingRowWithoutResult() throws Exception {
        long requested = createRequested(9_200_510L, "2026-08-11T05:10:00Z");
        String before = "{\"decision\":\"REDRIVE_REQUIRED\"}";
        String ack = "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}";

        assertThat(repository.recordPublished(requested, before, ack)).isFalse();
        assertThat(repository.tryStart(requested, Instant.parse("2026-08-11T05:11:00Z"))).isTrue();
        assertThat(repository.recordPublished(requested, before, ack)).isTrue();
        assertThat(repository.recordPublished(requested, "{}", "{}")).isFalse();

        var loaded = repository.findById(requested).orElseThrow();
        assertJsonEquals(before, loaded.getBeforeState());
        assertJsonEquals(ack, loaded.getResult());
    }

    @Test
    void findsOnlyRecentPublishedRedrivesInStartedOrder() {
        long requested = createRequested(9_200_520L, "2026-08-11T05:20:00Z");
        long unpublished = createRequested(9_200_521L, "2026-08-11T05:21:00Z");
        long tooOld = createRequested(9_200_522L, "2026-08-11T05:22:00Z");
        long second = createRequested(9_200_523L, "2026-08-11T05:23:00Z");
        long first = createRequested(9_200_524L, "2026-08-11T05:24:00Z");
        long resolved = createRequested(9_200_525L, "2026-08-11T05:25:00Z");
        Instant threshold = Instant.parse("2026-08-11T05:30:00Z");

        repository.tryStart(unpublished, threshold);
        startAndRecordPublished(tooOld, Instant.parse("2026-08-11T05:29:59.999999Z"), 1);
        startAndRecordPublished(second, Instant.parse("2026-08-11T05:32:00Z"), 2);
        startAndRecordPublished(first, Instant.parse("2026-08-11T05:31:00Z"), 3);
        startAndRecordPublished(resolved, Instant.parse("2026-08-11T05:33:00Z"), 4);
        assertThat(repository.resolve(
            resolved,
            "{\"decision\":\"ALREADY_APPLIED\"}",
            Instant.parse("2026-08-11T05:34:00Z"))).isTrue();

        assertThat(repository.findConverging(threshold, 2))
            .extracting(redrive -> redrive.getId())
            .containsExactly(first, second);
        assertThat(repository.findConverging(threshold, 10))
            .extracting(redrive -> redrive.getId())
            .doesNotContain(requested, unpublished, tooOld, resolved);
    }

    @Test
    void resolvesAlreadyAppliedOnlyFromRedrivingAndPersistsOutcome() throws Exception {
        long id = createRequested(9_200_530L, "2026-08-11T05:30:00Z");
        String before = "{\"decision\":\"ALREADY_APPLIED\"}";
        String after = "{\"decision\":\"ALREADY_APPLIED\"}";
        String result = "{\"outcome\":\"ALREADY_APPLIED\"}";
        Instant completedAt = Instant.parse("2026-08-11T05:31:02.123456Z");

        assertThat(repository.resolveAlreadyApplied(id, before, after, result, completedAt)).isFalse();
        repository.tryStart(id, Instant.parse("2026-08-11T05:31:00Z"));
        assertThat(repository.resolveAlreadyApplied(id, before, after, result, completedAt)).isTrue();
        assertThat(repository.resolveAlreadyApplied(id, "{}", "{}", "{}", completedAt)).isFalse();

        var loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED);
        assertThat(loaded.getFailureStage()).isNull();
        assertJsonEquals(before, loaded.getBeforeState());
        assertJsonEquals(after, loaded.getAfterState());
        assertJsonEquals(result, loaded.getResult());
        assertThat(loaded.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void rejectsOnlyFromRedrivingAndPersistsInspectionEvidence() throws Exception {
        long id = createRequested(9_200_540L, "2026-08-11T05:40:00Z");
        String before = "{\"decision\":\"NOT_ELIGIBLE\"}";
        String after = "{\"decision\":\"NOT_ELIGIBLE\"}";
        Instant completedAt = Instant.parse("2026-08-11T05:41:02.123456Z");

        assertThat(repository.reject(id, before, after, "INCONSISTENT_DOWNSTREAM_STATE", completedAt))
            .isFalse();
        repository.tryStart(id, Instant.parse("2026-08-11T05:41:00Z"));
        assertThat(repository.reject(id, before, after, "INCONSISTENT_DOWNSTREAM_STATE", completedAt))
            .isTrue();
        assertThat(repository.reject(id, "{}", "{}", "changed", completedAt)).isFalse();

        var loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REJECTED);
        assertThat(loaded.getFailureStage()).isNull();
        assertJsonEquals(before, loaded.getBeforeState());
        assertJsonEquals(after, loaded.getAfterState());
        assertThat(loaded.getLastError()).isEqualTo("INCONSISTENT_DOWNSTREAM_STATE");
        assertThat(loaded.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void resolvesOnlyFromRedrivingAndPreservesPublishedEvidence() throws Exception {
        long id = createRequested(9_200_550L, "2026-08-11T05:50:00Z");
        String before = "{\"decision\":\"REDRIVE_REQUIRED\"}";
        String ack = "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}";
        String after = "{\"decision\":\"ALREADY_APPLIED\"}";
        Instant completedAt = Instant.parse("2026-08-11T05:51:02.123456Z");

        assertThat(repository.resolve(id, after, completedAt)).isFalse();
        repository.tryStart(id, Instant.parse("2026-08-11T05:51:00Z"));
        repository.recordPublished(id, before, ack);
        assertThat(repository.resolve(id, after, completedAt)).isTrue();
        assertThat(repository.resolve(id, "{}", completedAt)).isFalse();

        var loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.RESOLVED);
        assertThat(loaded.getFailureStage()).isNull();
        assertJsonEquals(before, loaded.getBeforeState());
        assertJsonEquals(ack, loaded.getResult());
        assertJsonEquals(after, loaded.getAfterState());
        assertThat(loaded.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void mapsFullyPopulatedRedriveRowWithoutLosingAuditFieldsOrTimestamps() throws Exception {
        long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(9_200_560L);
        String before = "{\"decision\":\"REDRIVE_REQUIRED\"}";
        String after = "{\"decision\":\"UNKNOWN\"}";
        String result = "{\"topic\":\"payment.cancelled\",\"partition\":1,\"offset\":99}";
        Instant requestedAt = Instant.parse("2026-08-11T05:56:00.123456Z");
        Instant startedAt = Instant.parse("2026-08-11T05:56:01.234567Z");
        Instant completedAt = Instant.parse("2026-08-11T05:56:02.345678Z");
        jdbc.update("""
            INSERT INTO cancel_outbox_redrive
                (source_outbox_id, status, failure_stage, requested_by, reason, requested_at,
                 started_at, completed_at, result, last_error, before_state, after_state)
            VALUES (?, 'FAILED', 'PUBLISH', 'operator-full', 'mapping fixture', ?, ?, ?,
                    CAST(? AS JSON), 'broker unavailable', CAST(? AS JSON), CAST(? AS JSON))
            """, outboxId, requestedAt, startedAt, completedAt, result, before, after);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        var loaded = repository.findById(id).orElseThrow();

        assertThat(loaded.getSourceOutboxId()).isEqualTo(outboxId);
        assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.FAILED);
        assertThat(loaded.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.PUBLISH);
        assertThat(loaded.getRequestedBy()).isEqualTo("operator-full");
        assertThat(loaded.getReason()).isEqualTo("mapping fixture");
        assertThat(loaded.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(loaded.getStartedAt()).isEqualTo(startedAt);
        assertThat(loaded.getCompletedAt()).isEqualTo(completedAt);
        assertJsonEquals(result, loaded.getResult());
        assertThat(loaded.getLastError()).isEqualTo("broker unavailable");
        assertJsonEquals(before, loaded.getBeforeState());
        assertJsonEquals(after, loaded.getAfterState());
    }

    private long createRequested(long cancelRequestId, String requestedAt) {
        long outboxId = seedCancelledPaymentCompletedRequestAndDeadOutbox(cancelRequestId);
        return repository.createRequested(
            outboxId, "operator-1", "lifecycle", Instant.parse(requestedAt)).getId();
    }

    private void startAndRecordPublished(long redriveId, Instant startedAt, long offset) {
        assertThat(repository.tryStart(redriveId, startedAt)).isTrue();
        assertThat(repository.recordPublished(
            redriveId,
            "{\"decision\":\"REDRIVE_REQUIRED\"}",
            "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":" + offset + "}"))
            .isTrue();
    }

    private void retireExistingActiveRedrives() {
        jdbc.update("""
            UPDATE cancel_outbox_redrive
               SET status = 'REJECTED', completed_at = CURRENT_TIMESTAMP(6)
             WHERE status IN ('REQUESTED', 'REDRIVING')
            """);
    }

    private void assertJsonEquals(String expected, String actual) throws Exception {
        assertThat(OBJECT_MAPPER.readTree(actual)).isEqualTo(OBJECT_MAPPER.readTree(expected));
    }

    private long seedCancelledPaymentCompletedRequestAndDeadOutbox(long cancelRequestId) {
        long paymentId = cancelRequestId - 100_000L;
        jdbc.update("""
            INSERT INTO payment
                (id, payment_key, merchant_id, user_id, pg_type, total_amount,
                 currency, status, order_id)
            VALUES (?, ?, 1, 7, 'CARD', 1000, 'KRW', 'CANCELLED', ?)
            """, paymentId, "pay_redrive_" + cancelRequestId, cancelRequestId);
        jdbc.update("""
            INSERT INTO cancel_request
                (id, payment_id, request_hash, cancel_amount, cancel_reason, status,
                 completed_at, cancel_item_ids)
            VALUES (?, ?, ?, 1000, 'operator fixture', 'COMPLETED',
                    CURRENT_TIMESTAMP(3), JSON_ARRAY(1))
            """, cancelRequestId, paymentId, "redrive-hash-" + cancelRequestId);
        return seedOutbox(cancelRequestId, "DEAD");
    }

    private long seedOutbox(long cancelRequestId, String status) {
        jdbc.update("""
            INSERT INTO cancel_event_outbox (cancel_request_id, payload, status)
            VALUES (?, JSON_OBJECT('cancelRequestId', ?), ?)
            """, cancelRequestId, cancelRequestId, status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertTerminalRedrive(long outboxId, String status) {
        String failureStage = status.equals("FAILED") ? "PUBLISH" : null;
        jdbc.update("""
            INSERT INTO cancel_outbox_redrive
                (source_outbox_id, status, failure_stage, requested_by, reason, requested_at,
                 completed_at)
            VALUES (?, ?, ?, 'previous-operator', 'previous attempt', CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6))
            """, outboxId, status, failureStage);
    }
}
