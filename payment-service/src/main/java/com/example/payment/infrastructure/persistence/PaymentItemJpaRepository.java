package com.example.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * PaymentItem Spring Data JPA Repository
 */
public interface PaymentItemJpaRepository extends JpaRepository<PaymentItemJpaEntity, Long> {

    List<PaymentItemJpaEntity> findAllByPaymentIdOrderByIdAsc(Long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PaymentItemJpaEntity i WHERE i.paymentId = :paymentId ORDER BY i.id ASC")
    List<PaymentItemJpaEntity> findAllByPaymentIdForUpdate(@Param("paymentId") Long paymentId);
}
