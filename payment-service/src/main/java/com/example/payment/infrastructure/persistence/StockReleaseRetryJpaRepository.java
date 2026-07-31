package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockReleaseRetryJpaRepository
    extends JpaRepository<StockReleaseRetryJpaEntity, Long> {

    boolean existsByPaymentKey(String paymentKey);

    @Query("SELECT e FROM StockReleaseRetryJpaEntity e " +
           "WHERE e.status = 'PENDING' AND e.nextRetryAt <= :now " +
           "ORDER BY e.nextRetryAt ASC")
    List<StockReleaseRetryJpaEntity> findDueForRetry(@Param("now") LocalDateTime now);
}
