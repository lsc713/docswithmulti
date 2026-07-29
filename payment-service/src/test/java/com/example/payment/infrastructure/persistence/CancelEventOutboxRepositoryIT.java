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

    @Test
    @DisplayName("findPendingBatch는 retry_count를 포함해 반환한다")
    void find_pending_batch_returns_retry_count() {
        repo.insertPending(6001L, "{}");
        assertThat(repo.findPendingBatch(10).get(0).retryCount()).isZero();
    }

    @Test
    @DisplayName("bumpRetry는 retry_count를 1 증가시키고 last_error를 기록하며 PENDING을 유지한다")
    void bump_retry_increments_and_keeps_pending() {
        repo.insertPending(6002L, "{}");
        long id = repo.findPendingBatch(10).get(0).id();

        repo.bumpRetry(id, "boom");

        CancelEventOutboxRepository.PendingOutbox after = repo.findPendingBatch(10).stream()
            .filter(o -> o.id() == id)
            .findFirst()
            .orElseThrow();
        assertThat(after.retryCount()).isEqualTo(1);

        CancelEventOutboxJpaEntity entity = jpa.findById(id).orElseThrow();
        assertThat(entity.getLastError()).isEqualTo("boom");
        assertThat(entity.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("markDead는 status를 DEAD로 바꾸고 findPendingBatch에서 제외한다")
    void mark_dead_excludes_from_pending_batch() {
        repo.insertPending(6003L, "{}");
        long id = repo.findPendingBatch(10).get(0).id();

        repo.markDead(id, "poison");

        assertThat(repo.findPendingBatch(10)).isEmpty();
        CancelEventOutboxJpaEntity entity = jpa.findById(id).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("DEAD");
        assertThat(entity.getLastError()).isEqualTo("poison");
    }

    @Test
    @DisplayName("purgePublished는 retentionDays 초과 PUBLISHED만 삭제하고 PENDING/최근 PUBLISHED는 보존한다")
    void purge_published_deletes_only_old_published() {
        repo.insertPending(7001L, "{}"); // 오래된 PUBLISHED로 만들 대상
        repo.insertPending(7002L, "{}"); // 최근 PUBLISHED로 남길 대상
        repo.insertPending(7003L, "{}"); // PENDING으로 남길 대상

        List<CancelEventOutboxRepository.PendingOutbox> all = repo.findPendingBatch(10);
        long oldId = all.stream().filter(o -> o.cancelRequestId() == 7001L).findFirst().orElseThrow().id();
        long recentId = all.stream().filter(o -> o.cancelRequestId() == 7002L).findFirst().orElseThrow().id();

        repo.markPublished(List.of(oldId, recentId));
        jdbcTemplateForTest().update(
            "UPDATE cancel_event_outbox SET published_at = CURRENT_TIMESTAMP(3) - INTERVAL 8 DAY WHERE id = ?",
            oldId);

        int deleted = repo.purgePublished(7);

        assertThat(deleted).isEqualTo(1);
        List<CancelEventOutboxJpaEntity> remaining = jpa.findAll();
        assertThat(remaining).extracting(CancelEventOutboxJpaEntity::getCancelRequestId)
            .containsExactlyInAnyOrder(7002L, 7003L);
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplateForTest() {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }
}
