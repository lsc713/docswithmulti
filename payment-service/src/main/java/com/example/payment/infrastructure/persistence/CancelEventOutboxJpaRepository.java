package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CancelEventOutboxJpaRepository
    extends JpaRepository<CancelEventOutboxJpaEntity, Long> {

    boolean existsByCancelRequestId(Long cancelRequestId);

    @Modifying
    @Query("UPDATE CancelEventOutboxJpaEntity o SET o.status = 'PUBLISHED', o.publishedAt = CURRENT_TIMESTAMP WHERE o.cancelRequestId = :cancelRequestId")
    int markPublished(@Param("cancelRequestId") Long cancelRequestId);

    @Query("SELECT o FROM CancelEventOutboxJpaEntity o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC LIMIT :limit")
    List<CancelEventOutboxJpaEntity> findPendingBatch(@Param("limit") int limit);
}
