package com.example.payment.domain.entity;

public enum CancelOutboxRedriveFailureCode {
    PREFLIGHT_UNKNOWN,
    KAFKA_TIMEOUT,
    KAFKA_SEND_FAILED,
    PUBLISH_STATE_UNKNOWN,
    CONVERGENCE_TIMEOUT,
    DOWNSTREAM_UNKNOWN,
    INCONSISTENT_DOWNSTREAM_STATE
}
