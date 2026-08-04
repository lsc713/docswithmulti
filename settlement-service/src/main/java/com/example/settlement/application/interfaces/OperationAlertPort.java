package com.example.settlement.application.interfaces;

/**
 * 운영팀 알림 계약 (product/payment OperationAlertPort 복제). 현재 구현: LogOperationAlertAdapter (log.error).
 * reconcile 드리프트·finalize 경합·FINALIZED 늦은 이벤트를 삼키지 않고 알린다. alert(String) 단일 메서드만 필요(YAGNI).
 */
public interface OperationAlertPort {
    void alert(String message);
}
