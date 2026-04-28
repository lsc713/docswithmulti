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
}
