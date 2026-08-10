package com.example.payment.application.usecase;

import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;

public interface CancelOutboxInspectionUseCase {

    Result inspect(long outboxId);

    record Result(
        long outboxId,
        long cancelRequestId,
        CancelOutboxDecision decision,
        CancelOutboxReasonCode reasonCode,
        CancelRestoreLegSnapshot order,
        CancelRestoreLegSnapshot stock
    ) {}
}
