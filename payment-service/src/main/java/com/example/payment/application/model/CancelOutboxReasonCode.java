package com.example.payment.application.model;

public enum CancelOutboxReasonCode {
    OUTBOX_NOT_DEAD,
    CANCEL_NOT_COMPLETED,
    PAYMENT_NOT_CANCELLED,
    INVALID_PAYLOAD,
    INCONSISTENT_DOWNSTREAM_STATE,
    DOWNSTREAM_UNKNOWN
}
