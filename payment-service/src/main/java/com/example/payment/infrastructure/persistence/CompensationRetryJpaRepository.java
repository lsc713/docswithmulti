package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CompensationRetryJpaRepository
    extends JpaRepository<CompensationRetryJpaEntity, Long> {

    @Query("SELECT e FROM CompensationRetryJpaEntity e " +
           "WHERE e.status = 'PENDING' AND e.nextRetryAt <= :now " +
           "ORDER BY e.nextRetryAt ASC")
    List<CompensationRetryJpaEntity> findDueForRetry(@Param("now") LocalDateTime now);
}
