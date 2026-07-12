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

    CancelEventOutboxRepository repo;

    @BeforeEach
    void setUp() {
        repo = new CancelEventOutboxRepositoryImpl(jpa);
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
        repo.markPublished(id);
        assertThat(repo.findPendingBatch(10)).isEmpty();
    }
}
