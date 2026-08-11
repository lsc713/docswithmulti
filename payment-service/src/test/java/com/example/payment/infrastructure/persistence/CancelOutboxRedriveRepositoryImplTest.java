package com.example.payment.infrastructure.persistence;

import com.example.payment.application.exception.ActiveRedriveExistsException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelOutboxRedriveRepositoryImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private CancelOutboxRedriveRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new CancelOutboxRedriveRepositoryImpl(jdbc);
    }

    @Test
    void mapsOnlyActiveRedriveUniqueConstraintToActiveConflict() {
        DuplicateKeyException duplicate = new DuplicateKeyException(
            "Duplicate entry '41' for key 'uk_cancel_outbox_redrive_active'");
        stubDeadSourceAndEmptyHistory();
        when(jdbc.update(any(String.class), any(SqlParameterSource.class),
            any(GeneratedKeyHolder.class), any(String[].class))).thenThrow(duplicate);

        assertThatThrownBy(() -> repository.createRequested(
            41L, "operator-1", "retry", Instant.parse("2026-08-11T06:00:00Z")))
            .isInstanceOf(ActiveRedriveExistsException.class);
    }

    @Test
    void rethrowsDuplicateForAnUnrelatedConstraint() {
        DuplicateKeyException duplicate = new DuplicateKeyException(
            "Duplicate entry '41' for key 'uk_unrelated_constraint'");
        stubDeadSourceAndEmptyHistory();
        when(jdbc.update(any(String.class), any(SqlParameterSource.class),
            any(GeneratedKeyHolder.class), any(String[].class))).thenThrow(duplicate);

        assertThatThrownBy(() -> repository.createRequested(
            41L, "operator-1", "retry", Instant.parse("2026-08-11T06:00:00Z")))
            .isSameAs(duplicate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubDeadSourceAndEmptyHistory() {
        when(jdbc.query(contains("cancel_event_outbox"), any(SqlParameterSource.class),
            any(RowMapper.class))).thenReturn(List.of("DEAD"));
        when(jdbc.query(contains("cancel_outbox_redrive"), any(SqlParameterSource.class),
            any(RowMapper.class))).thenReturn(List.of());
    }
}
