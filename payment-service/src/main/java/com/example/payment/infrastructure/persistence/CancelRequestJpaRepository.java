package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequest Spring Data JPA Repository
 */
public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {

    Optional<CancelRequestJpaEntity> findByPaymentIdAndRequestHash(Long paymentId, String requestHash);

    // pending-recovery: PENDING + createdAt 기준
    List<CancelRequestJpaEntity> findByStatusAndCreatedAtBefore(CancelStatus status, LocalDateTime before);

    // processing-recovery: PROCESSING + updatedAt 기준
    List<CancelRequestJpaEntity> findByStatusAndUpdatedAtBefore(CancelStatus status, LocalDateTime before);
}
