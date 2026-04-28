package com.example.payment.application.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * TX3 커밋 후 Kafka 발행 트리거.
 * CancelTxWriter.saveTx3()에서 ApplicationEventPublisher로 발행.
 * CancelEventPublisher가 AFTER_COMMIT으로 수신.
 */
public record CancelCompletedEvent(
    long cancelRequestId,
    String paymentKey,
    long merchantId,
    Instant cancelledAt,
    List<CancelledItemData> cancelledItems
) {
    public record CancelledItemData(
        long paymentItemId,
        long orderItemId,
        BigDecimal itemAmount
    ) {}
}
