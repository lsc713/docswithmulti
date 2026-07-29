package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

public class CancelEventOutboxRepositoryImpl implements CancelEventOutboxRepository {

    private final CancelEventOutboxJpaRepository jpaRepository;          // insertPending — 메인 풀/TX3
    private final NamedParameterJdbcTemplate outboxJdbc;                 // find+mark — 전용 풀

    public CancelEventOutboxRepositoryImpl(
            CancelEventOutboxJpaRepository jpaRepository,
            NamedParameterJdbcTemplate outboxJdbc) {
        this.jpaRepository = jpaRepository;
        this.outboxJdbc = outboxJdbc;
    }

    @Override
    public void insertPending(long cancelRequestId, String payload) {
        // 취소 TX3 안 — 메인 풀 유지(비즈니스 커밋과 원자적)
        jpaRepository.insertPendingIdempotent(cancelRequestId, payload);
    }

    @Override
    public List<PendingOutbox> findPendingBatch(int limit) {
        // 폴러 배수 경로 — 전용 풀
        return outboxJdbc.query(
            "SELECT id, cancel_request_id, payload, retry_count FROM cancel_event_outbox "
                + "WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit",
            new MapSqlParameterSource("limit", limit),
            (rs, n) -> new PendingOutbox(
                rs.getLong("id"), rs.getLong("cancel_request_id"), rs.getString("payload"),
                rs.getInt("retry_count")));
    }

    @Override
    public void markPublished(List<Long> outboxIds) {
        if (outboxIds.isEmpty()) {
            return; // WHERE id IN () 방지
        }
        // 폴러 배수 경로 — 전용 풀. 배치 UPDATE(커넥션 1회).
        outboxJdbc.update(
            "UPDATE cancel_event_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3) "
                + "WHERE id IN (:ids)",
            new MapSqlParameterSource("ids", outboxIds));
    }

    @Override
    public void bumpRetry(long id, String lastError) {
        // 폴러 배수 경로 — 전용 풀. PENDING 유지(다음 폴에서 재시도).
        outboxJdbc.update(
            "UPDATE cancel_event_outbox SET retry_count = retry_count + 1, last_error = :err "
                + "WHERE id = :id",
            new MapSqlParameterSource("err", truncate(lastError)).addValue("id", id));
    }

    @Override
    public void markDead(long id, String lastError) {
        // 폴러 배수 경로 — 전용 풀. DEAD 처리 후 findPendingBatch에서 제외.
        outboxJdbc.update(
            "UPDATE cancel_event_outbox SET status = 'DEAD', last_error = :err WHERE id = :id",
            new MapSqlParameterSource("err", truncate(lastError)).addValue("id", id));
    }

    @Override
    public int purgePublished(int retentionDays) {
        // 폴러 배수 경로 — 전용 풀. retentionDays 초과한 PUBLISHED 행만 삭제.
        return outboxJdbc.update(
            "DELETE FROM cancel_event_outbox WHERE status = 'PUBLISHED' "
                + "AND published_at < (CURRENT_TIMESTAMP(3) - INTERVAL :days DAY)",
            new MapSqlParameterSource("days", retentionDays));
    }

    private static String truncate(String error) {
        return error != null ? error.substring(0, Math.min(500, error.length())) : null;
    }
}
