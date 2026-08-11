package com.example.payment.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private long seedSourceOutbox() {
        jdbc.update("""
            INSERT INTO cancel_event_outbox (cancel_request_id, payload, status)
            VALUES (9900001, JSON_OBJECT(), 'DEAD')
            """);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
