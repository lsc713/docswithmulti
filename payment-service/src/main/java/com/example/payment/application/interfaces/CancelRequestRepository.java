package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.CancelStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * CancelRequest 영속성 인터페이스
 * infrastructure/persistence에서 JPA로 구현
 */
public interface CancelRequestRepository {

    Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash);

    CancelRequest save(CancelRequest cancelRequest);

    /** 복구 스케줄러용: 특정 상태 + 기준 시각 이전 건 조회 */
    List<CancelRequest> findByStatusAndCreatedAtBefore(CancelStatus status, Instant before);
}
