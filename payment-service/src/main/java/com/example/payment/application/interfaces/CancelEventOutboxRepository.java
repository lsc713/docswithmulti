package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelRequest;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.PaymentItem;

import java.util.List;

public interface CancelEventOutboxRepository {

    /** TX3 내부에서 호출. cancel_request_id UK로 중복 방어. */
    void insertIfAbsent(CancelRequest cancelRequest, Payment payment, List<PaymentItem> cancelledItems);

    /** 스케줄러용: PENDING 건 최대 limit개 오래된 순 조회. */
    List<PendingOutbox> findPendingBatch(int limit);

    /** Kafka 발행 성공 후 PUBLISHED로 마킹. */
    void markPublished(long cancelRequestId);
}
