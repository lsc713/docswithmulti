package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
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
                CancelOutboxRedriveFailureCode.valueOf(result.reasonCode().name()),
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
}
