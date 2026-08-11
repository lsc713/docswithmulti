package com.example.payment.application.usecase;

import com.example.payment.domain.entity.CancelOutboxRedrive;

public interface CancelOutboxRedriveUseCase {
    CancelOutboxRedrive request(long outboxId, String requestedBy, String reason);
}
