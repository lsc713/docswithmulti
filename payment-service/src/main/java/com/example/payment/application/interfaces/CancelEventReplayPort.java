package com.example.payment.application.interfaces;

public interface CancelEventReplayPort {

    ReplayResult replay(long cancelRequestId, String payload);

    record ReplayResult(String topic, int partition, long offset) {}
}
