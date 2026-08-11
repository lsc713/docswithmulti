package com.example.payment.infrastructure.persistence;

import com.example.payment.application.exception.ActiveRedriveExistsException;
import com.example.payment.application.exception.CancelOutboxNotDeadException;
import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.RedriveAlreadyResolvedException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public class CancelOutboxRedriveRepositoryImpl implements CancelOutboxRedriveRepository {

    private static final RowMapper<CancelOutboxRedrive> ROW_MAPPER = (rs, rowNum) ->
        CancelOutboxRedrive.reconstitute(
            rs.getLong("id"),
            rs.getLong("source_outbox_id"),
            CancelOutboxRedriveStatus.valueOf(rs.getString("status")),
            enumOrNull(rs.getString("failure_stage"), CancelOutboxRedriveFailureStage.class),
            rs.getString("requested_by"),
            rs.getString("reason"),
            instantOrNull(rs.getTimestamp("requested_at")),
            instantOrNull(rs.getTimestamp("started_at")),
            instantOrNull(rs.getTimestamp("completed_at")),
            rs.getString("result"),
            rs.getString("last_error"),
            rs.getString("before_state"),
            rs.getString("after_state"));

    private final NamedParameterJdbcTemplate jdbc;

    public CancelOutboxRedriveRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CancelOutboxRedrive createRequested(
        long sourceOutboxId, String requestedBy, String reason, Instant requestedAt
    ) {
        MapSqlParameterSource sourceParameters = new MapSqlParameterSource("sourceOutboxId", sourceOutboxId);
        List<String> sourceStatuses = jdbc.query("""
            SELECT status
              FROM cancel_event_outbox
             WHERE id = :sourceOutboxId
             FOR UPDATE
            """, sourceParameters, (rs, rowNum) -> rs.getString("status"));
        if (sourceStatuses.isEmpty()) {
            throw new CancelOutboxNotFoundException(sourceOutboxId);
        }
        if (!"DEAD".equals(sourceStatuses.getFirst())) {
            throw new CancelOutboxNotDeadException(sourceOutboxId);
        }

        List<CancelOutboxRedriveStatus> history = jdbc.query("""
            SELECT status
              FROM cancel_outbox_redrive
             WHERE source_outbox_id = :sourceOutboxId
             ORDER BY id DESC
            """, sourceParameters,
            (rs, rowNum) -> CancelOutboxRedriveStatus.valueOf(rs.getString("status")));
        if (history.stream().anyMatch(CancelOutboxRedriveStatus::isActive)) {
            throw new ActiveRedriveExistsException(sourceOutboxId);
        }
        if (history.stream().anyMatch(CancelOutboxRedriveStatus::isResolved)) {
            throw new RedriveAlreadyResolvedException(sourceOutboxId);
        }

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update("""
                INSERT INTO cancel_outbox_redrive
                    (source_outbox_id, status, requested_by, reason, requested_at)
                VALUES (:sourceOutboxId, 'REQUESTED', :requestedBy, :reason, :requestedAt)
                """, new MapSqlParameterSource()
                    .addValue("sourceOutboxId", sourceOutboxId)
                    .addValue("requestedBy", requestedBy)
                    .addValue("reason", reason)
                    .addValue("requestedAt", Timestamp.from(requestedAt)),
                keyHolder, new String[] {"id"});
        } catch (DuplicateKeyException exception) {
            if (isActiveRedriveUniqueConstraint(exception)) {
                throw new ActiveRedriveExistsException(sourceOutboxId);
            }
            throw exception;
        }

        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("Cancel outbox redrive insert did not return an id");
        }
        return findById(generatedId.longValue()).orElseThrow(() ->
            new IllegalStateException("Inserted cancel outbox redrive was not found"));
    }

    @Override
    public Optional<CancelOutboxRedrive> findById(long redriveId) {
        List<CancelOutboxRedrive> rows = jdbc.query("""
            SELECT id, source_outbox_id, status, failure_stage, requested_by, reason, requested_at,
                   started_at, completed_at, result, last_error, before_state, after_state
              FROM cancel_outbox_redrive
             WHERE id = :redriveId
            """, new MapSqlParameterSource("redriveId", redriveId), ROW_MAPPER);
        return rows.stream().findFirst();
    }

    private static boolean isActiveRedriveUniqueConstraint(DuplicateKeyException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("uk_cancel_outbox_redrive_active")) {
                return true;
            }
        }
        return false;
    }

    private static Instant instantOrNull(Timestamp value) {
        return value != null ? value.toInstant() : null;
    }

    private static <T extends Enum<T>> T enumOrNull(String value, Class<T> type) {
        return value != null ? Enum.valueOf(type, value) : null;
    }
}
