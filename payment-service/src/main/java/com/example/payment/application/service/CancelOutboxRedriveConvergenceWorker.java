package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveConvergenceWorker {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxInspectionUseCase inspection;
    private final CancelOutboxRedriveAuditJson auditJson;
    private final CancelOutboxRedriveTelemetry telemetry;
    private final Clock clock;

    public CancelOutboxRedriveConvergenceWorker(
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
        var result = inspection.inspect(redrive.getSourceOutboxId());
        if (result.decision() == CancelOutboxDecision.ALREADY_APPLIED) {
            if (repository.resolve(redrive.getId(), auditJson.inspection(result), clock.instant())) {
                telemetry.terminal(redrive, CancelOutboxRedriveStatus.RESOLVED, null, null);
            }
        }
    }
}
