package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;
import java.util.List;

public interface CancelRequestHistoryRepository {
    /** TX 밖에서 별도 INSERT (단건). 실패해도 비즈니스 로직에 영향 없음. */
    void record(long cancelRequestId, CancelStatus status, String reason);

    /** 여러 이력을 단일 트랜잭션(REQUIRES_NEW)으로 일괄 INSERT — 커밋 1개. */
    void recordAll(List<CancelHistoryEntry> entries);
}
