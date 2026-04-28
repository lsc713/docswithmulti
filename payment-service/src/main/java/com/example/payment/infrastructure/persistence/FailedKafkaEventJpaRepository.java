package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface FailedKafkaEventJpaRepository
    extends JpaRepository<FailedKafkaEventJpaEntity, Long> {

    boolean existsByCancelRequestId(long cancelRequestId);

    @Query(value = "SELECT e FROM FailedKafkaEventJpaEntity e " +
                   "WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC LIMIT :limit")
    List<FailedKafkaEventJpaEntity> findPendingBatch(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.status = 'PUBLISHED', e.updatedAt = :now WHERE e.cancelRequestId = :id")
    void markPublished(@Param("id") long cancelRequestId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.retryCount = e.retryCount + 1, e.lastError = :error, e.updatedAt = :now " +
           "WHERE e.cancelRequestId = :id")
    void incrementRetry(@Param("id") long cancelRequestId,
                        @Param("error") String error,
                        @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FailedKafkaEventJpaEntity e " +
           "SET e.status = 'EXHAUSTED', e.lastError = :error, e.updatedAt = :now " +
           "WHERE e.cancelRequestId = :id")
    void markExhausted(@Param("id") long cancelRequestId,
                       @Param("error") String error,
                       @Param("now") Instant now);
}
