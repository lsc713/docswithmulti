package com.example.payment.infrastructure.persistence;

import com.example.payment.application.exception.CancelOutboxNotDeadException;
import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.RedriveAlreadyResolvedException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelOutboxRedriveRepositoryIT extends AbstractRepositoryTest {

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
