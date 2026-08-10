package com.example.payment.application.model;

public enum CancelOutboxDecision {
    REDRIVE_REQUIRED,
    ALREADY_APPLIED,
    NOT_ELIGIBLE,
    UNKNOWN
}
