package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelRequestRepository {

    Optional<CancelRequest> findByPaymentIdAndRequestHash(long paymentId, String requestHash);

    CancelRequest save(CancelRequest cancelRequest);

    /**
     * pending-recovery용: PENDING + createdAt < before
     * TX1 이후 상태 변경 없는 건 → createdAt 기준
     */
    List<CancelRequest> findPendingCreatedBefore(Instant before);

    /**
     * processing-recovery용: PROCESSING + updatedAt < before
     * TX2(PROCESSING UPDATE)가 updatedAt 기준점 → updatedAt 기준
     */
    List<CancelRequest> findProcessingUpdatedBefore(Instant before);

    /**
     * pg_retry_count 원자 UPDATE(read-modify-write 경쟁 제거, D-04).
     * 호출 후 로컬 CancelRequest 객체는 stale — 임계값 비교 전 반드시 재조회할 것.
     * @return 1 = 성공, 0 = 대상 없음
     */
    int incrementPgRetryCount(long id);
}
