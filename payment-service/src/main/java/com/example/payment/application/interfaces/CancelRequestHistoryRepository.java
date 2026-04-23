package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;

public interface CancelRequestHistoryRepository {
    /** TX 밖에서 별도 INSERT. 실패해도 비즈니스 로직에 영향 없음. */
    void record(long cancelRequestId, CancelStatus status, String reason);
}
