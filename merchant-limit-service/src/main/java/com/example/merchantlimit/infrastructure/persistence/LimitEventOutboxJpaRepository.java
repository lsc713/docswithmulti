package com.example.merchantlimit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface LimitEventOutboxJpaRepository
    extends JpaRepository<LimitEventOutboxJpaEntity, Long> {
    List<LimitEventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(
        String status, Pageable pageable);
}
