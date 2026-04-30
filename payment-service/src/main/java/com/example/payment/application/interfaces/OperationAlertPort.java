package com.example.payment.application.interfaces;

import java.time.Instant;

/**
 * 운영팀 알림 계약.
 * 현재 구현: LogOperationAlertAdapter (log.error).
 * 추후 Slack/PagerDuty 교체 가능.
 */
public interface OperationAlertPort {
    void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince);
    void alert(String message);
}
