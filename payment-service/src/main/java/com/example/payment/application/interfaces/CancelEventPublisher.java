package com.example.payment.application.interfaces;

/** TX3 마지막에 호출. 모드에 따라 인라인 발행/아웃박스 INSERT를 수행. */
public interface CancelEventPublisher {
    void publish(long cancelRequestId, String payload);
}
