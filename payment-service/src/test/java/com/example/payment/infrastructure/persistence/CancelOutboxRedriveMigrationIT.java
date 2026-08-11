package com.example.payment.infrastructure.persistence;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelOutboxRedriveMigrationIT extends AbstractRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rejectsFailedRedriveWithoutFailureStage() {
        long sourceOutboxId = seedSourceOutbox();

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO cancel_outbox_redrive
                (source_outbox_id, status, failure_stage, requested_by, reason, requested_at)
            VALUES (?, 'FAILED', NULL, 'operator-1', 'migration invariant', CURRENT_TIMESTAMP(6))
            """, sourceOutboxId))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("chk_cancel_outbox_redrive_failure_stage");
    }

    @Test
    void createsPollingIndexesWithExpectedColumnOrder() {
        assertThat(indexColumns("idx_cancel_outbox_redrive_requested_poll"))
            .containsExactly("status", "requested_at", "id");
        assertThat(indexColumns("idx_cancel_outbox_redrive_convergence_poll"))
            .containsExactly("status", "started_at", "id");
    }

    @Test
    void pollingQueryPlansUseDedicatedIndexes() {
        String requestedKey = jdbc.queryForObject("""
            EXPLAIN
            SELECT id
              FROM cancel_outbox_redrive
             WHERE status = 'REQUESTED'
             ORDER BY requested_at, id
             LIMIT 100
            """, (rs, rowNum) -> rs.getString("key"));
        String convergenceKey = jdbc.queryForObject("""
            EXPLAIN
            SELECT id, source_outbox_id, status, failure_stage, requested_by, reason, requested_at,
                   started_at, completed_at, result, last_error, before_state, after_state
              FROM cancel_outbox_redrive
             WHERE status = 'REDRIVING'
               AND result IS NOT NULL
               AND started_at >= '2026-08-11 00:00:00.000000'
             ORDER BY started_at, id
             LIMIT 100
            """, (rs, rowNum) -> rs.getString("key"));

        assertThat(requestedKey).isEqualTo("idx_cancel_outbox_redrive_requested_poll");
        assertThat(convergenceKey).isEqualTo("idx_cancel_outbox_redrive_convergence_poll");
    }

    private List<String> indexColumns(String indexName) {
        return jdbc.query("""
            SELECT column_name
              FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND table_name = 'cancel_outbox_redrive'
               AND index_name = ?
             ORDER BY seq_in_index
            """, (rs, rowNum) -> rs.getString("column_name"), indexName);
    }

    private long seedSourceOutbox() {
        jdbc.update("""
            INSERT INTO cancel_event_outbox (cancel_request_id, payload, status)
            VALUES (9900001, JSON_OBJECT(), 'DEAD')
            """);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
