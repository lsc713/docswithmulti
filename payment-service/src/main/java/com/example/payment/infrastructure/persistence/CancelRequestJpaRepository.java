package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.entity.CancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CancelRequest Spring Data JPA Repository
 */
public interface CancelRequestJpaRepository extends JpaRepository<CancelRequestJpaEntity, Long> {

    /**
     * paymentId로 모든 취소 요청 조회
     */
    List<CancelRequestJpaEntity> findAllByPaymentId(Long paymentId);

    /**
     * paymentId로 COMPLETED 상태인 취소 요청만 조회
     */
    @Query("SELECT c FROM CancelRequestJpaEntity c WHERE c.paymentId = :paymentId AND c.status = 'COMPLETED'")
    List<CancelRequestJpaEntity> findCompletedByPaymentId(@Param("paymentId") Long paymentId);

    /**
     * 지정된 시간 이전에 PROCESSING 상태로 스택된 취소 요청 조회
     * (서버 재시작 후 5분 초과 처리 중 건)
     */
    @Query("SELECT c FROM CancelRequestJpaEntity c WHERE c.status = 'PROCESSING' AND c.processingStartedAt < :before")
    List<CancelRequestJpaEntity> findStuckProcessingRequests(@Param("before") LocalDateTime before);
}
