package com.example.payment.infrastructure.persistence;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cancel_event_outbox 멱등 insert/조회/발행표시")
class CancelEventOutboxRepositoryIT extends AbstractRepositoryTest {

    @Autowired
    CancelEventOutboxJpaRepository jpa;

    @Autowired
    javax.sql.DataSource dataSource;

    CancelEventOutboxRepository repo;

    @BeforeEach
    void setUp() {
        var jdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource);
        repo = new CancelEventOutboxRepositoryImpl(jpa, jdbc);
    }

    @Test
    @DisplayName("같은 cancelRequestId 중복 insert는 예외 없이 1행")
    void idempotent_insert() {
        repo.insertPending(1001L, "{\"cancelRequestId\":1001}");
        repo.insertPending(1001L, "{\"cancelRequestId\":1001}"); // 중복 — 무시돼야
        List<CancelEventOutboxRepository.PendingOutbox> pending = repo.findPendingBatch(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).cancelRequestId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("markPublished 후 PENDING 조회에서 빠진다")
    void mark_published_excludes() {
        repo.insertPending(2002L, "{}");
        long id = repo.findPendingBatch(10).get(0).id();
        repo.markPublished(List.of(id));
        assertThat(repo.findPendingBatch(10)).isEmpty();
    }

    @Test
    @DisplayName("배치 markPublished는 주어진 id만 한 번에 PUBLISHED 처리한다")
    void batch_mark_published_marks_only_given_ids() {
        repo.insertPending(3001L, "{}");
        repo.insertPending(3002L, "{}");
        repo.insertPending(3003L, "{}");
        List<CancelEventOutboxRepository.PendingOutbox> before = repo.findPendingBatch(10);
        assertThat(before).hasSize(3);

        List<Long> toMark = before.stream()
            .filter(o -> o.cancelRequestId() == 3001L || o.cancelRequestId() == 3003L)
            .map(CancelEventOutboxRepository.PendingOutbox::id)
            .toList();
        repo.markPublished(toMark);

        List<CancelEventOutboxRepository.PendingOutbox> remaining = repo.findPendingBatch(10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).cancelRequestId()).isEqualTo(3002L);
    }

    @Test
    @DisplayName("빈 리스트 markPublished는 예외 없이 no-op")
    void batch_mark_published_empty_is_noop() {
        repo.insertPending(4001L, "{}");
        repo.markPublished(List.of());
        assertThat(repo.findPendingBatch(10)).hasSize(1);
    }

    @Test
    @DisplayName("insertPendingIdempotent 직후 엔티티 조회 시 retry_count=0, last_error=null")
    void insert_defaults_retry_columns() {
        repo.insertPending(5001L, "{}");
        CancelEventOutboxJpaEntity saved = jpa.findAll().stream()
            .filter(e -> e.getCancelRequestId() == 5001L)
            .findFirst()
            .orElseThrow();
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getLastError()).isNull();
    }
}
