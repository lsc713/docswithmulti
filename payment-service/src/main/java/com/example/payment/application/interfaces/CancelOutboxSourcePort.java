package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.PaymentStatus;

import java.util.Optional;

public interface CancelOutboxSourcePort {

    Optional<SourceSnapshot> findById(long outboxId);

    record SourceSnapshot(
        long outboxId,
        long cancelRequestId,
        String payload,
        String outboxStatus,
        CancelStatus cancelStatus,
        PaymentStatus paymentStatus
    ) {}
}
