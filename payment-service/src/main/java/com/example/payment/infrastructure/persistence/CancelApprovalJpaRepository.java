package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CancelApprovalJpaRepository extends JpaRepository<CancelApprovalJpaEntity, Long> {
    Optional<CancelApprovalJpaEntity> findFirstByPaymentIdAndStatus(long paymentId, String status);
    List<CancelApprovalJpaEntity> findByStatus(String status);
    Optional<CancelApprovalJpaEntity> findFirstByPaymentIdOrderByIdDesc(long paymentId);
}
