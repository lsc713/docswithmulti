package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequest Spring Data JPA Repository
 */
public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {

    Optional<CancelRequestJpaEntity> findByPaymentIdAndRequestHash(Long paymentId, String requestHash);

    List<CancelRequestJpaEntity> findByStatusAndCreatedAtBefore(CancelStatus status, LocalDateTime before);
}
