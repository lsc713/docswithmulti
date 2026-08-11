package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureCode;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveDeadlineWorker {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxInspectionUseCase inspection;
    private final CancelOutboxRedriveAuditJson auditJson;
    private final CancelOutboxRedriveTelemetry telemetry;
    private final Clock clock;

    public CancelOutboxRedriveDeadlineWorker(
        CancelOutboxRedriveRepository repository,
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxRedriveAuditJson auditJson,
        CancelOutboxRedriveTelemetry telemetry,
        Clock clock
    ) {
        this.repository = repository;
        this.inspection = inspection;
        this.auditJson = auditJson;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public void check(CancelOutboxRedrive redrive) {
        CancelOutboxInspectionUseCase.Result result;
        String snapshot;
        try {
            result = inspection.inspect(redrive.getSourceOutboxId());
            snapshot = auditJson.inspection(result);
        } catch (Exception exception) {
            failConvergence(
                redrive, CancelOutboxRedriveFailureCode.DOWNSTREAM_UNKNOWN,
                auditJson.unknownInspection());
            return;
        }
        switch (result.decision()) {
            case ALREADY_APPLIED -> {
                if (repository.resolve(redrive.getId(), snapshot, clock.instant())) {
                    telemetry.terminal(redrive, CancelOutboxRedriveStatus.RESOLVED, null, null);
                }
            }
            case REDRIVE_REQUIRED -> failConvergence(
                redrive, CancelOutboxRedriveFailureCode.CONVERGENCE_TIMEOUT, snapshot);
            case UNKNOWN -> failConvergence(
                redrive, CancelOutboxRedriveFailureCode.DOWNSTREAM_UNKNOWN, snapshot);
            case NOT_ELIGIBLE -> failConvergence(
                redrive,
                failureCode(result.reasonCode()),
                snapshot);
        }
    }

    private void failConvergence(
        CancelOutboxRedrive redrive,
        CancelOutboxRedriveFailureCode failureCode,
        String snapshot
    ) {
        if (repository.failConvergence(
            redrive.getId(), failureCode.name(), snapshot, clock.instant())) {
            telemetry.terminal(
                redrive,
                CancelOutboxRedriveStatus.FAILED,
                CancelOutboxRedriveFailureStage.CONVERGENCE,
                failureCode);
        }
    }

    private static CancelOutboxRedriveFailureCode failureCode(
        CancelOutboxReasonCode reasonCode
    ) {
        return switch (reasonCode) {
            case OUTBOX_NOT_DEAD -> CancelOutboxRedriveFailureCode.OUTBOX_NOT_DEAD;
            case CANCEL_NOT_COMPLETED -> CancelOutboxRedriveFailureCode.CANCEL_NOT_COMPLETED;
            case PAYMENT_NOT_CANCELLED -> CancelOutboxRedriveFailureCode.PAYMENT_NOT_CANCELLED;
            case INVALID_PAYLOAD -> CancelOutboxRedriveFailureCode.INVALID_PAYLOAD;
            case INCONSISTENT_DOWNSTREAM_STATE ->
                CancelOutboxRedriveFailureCode.INCONSISTENT_DOWNSTREAM_STATE;
            case DOWNSTREAM_UNKNOWN -> CancelOutboxRedriveFailureCode.DOWNSTREAM_UNKNOWN;
        };
    }
}
