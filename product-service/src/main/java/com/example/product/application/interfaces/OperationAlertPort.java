package com.example.product.application.interfaces;

/**
 * 운영팀 알림 계약 (payment OperationAlertPort 복제, 설계 §수정2 갭②).
 * 현재 구현: LogOperationAlertAdapter (log.error). 추후 Slack/PagerDuty 교체 가능.
 * product 는 alert(String) 단일 메서드만 필요 — payment 의 PG 전용 메서드는 복제하지 않는다(YAGNI).
 */
public interface OperationAlertPort {
    void alert(String message);
}
