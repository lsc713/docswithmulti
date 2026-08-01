package com.example.order.application.interfaces;

/**
 * 운영팀 알림 계약 (Phase 1 product OperationAlertPort 미러).
 * 현재 구현: LogOperationAlertAdapter (log.error). 추후 Slack/PagerDuty 교체 가능.
 * alert(String) 단일 메서드만 필요 (YAGNI).
 */
public interface OperationAlertPort {
    void alert(String message);
}
