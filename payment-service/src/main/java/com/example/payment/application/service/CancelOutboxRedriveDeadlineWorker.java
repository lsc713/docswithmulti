package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveDeadlineWorker {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxInspectionUseCase inspection;
    private final CancelOutboxRedriveAuditJson auditJson;
    private final Clock clock;

    public CancelOutboxRedriveDeadlineWorker(
        CancelOutboxRedriveRepository repository,
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxRedriveAuditJson auditJson,
        Clock clock
    ) {
        this.repository = repository;
        this.inspection = inspection;
        this.auditJson = auditJson;
        this.clock = clock;
    }

    public void check(CancelOutboxRedrive redrive) {
        try {
            var result = inspection.inspect(redrive.getSourceOutboxId());
            String snapshot = auditJson.inspection(result);
            switch (result.decision()) {
                case ALREADY_APPLIED -> repository.resolve(redrive.getId(), snapshot, clock.instant());
                case REDRIVE_REQUIRED -> failConvergence(redrive.getId(), "CONVERGENCE_TIMEOUT", snapshot);
                case UNKNOWN -> failConvergence(redrive.getId(), "DOWNSTREAM_UNKNOWN", snapshot);
                case NOT_ELIGIBLE -> failConvergence(redrive.getId(), result.reasonCode().name(), snapshot);
            }
        } catch (Exception exception) {
            failConvergence(redrive.getId(), "DOWNSTREAM_UNKNOWN", auditJson.unknownInspection());
        }
    }

    private void failConvergence(long redriveId, String failureCode, String snapshot) {
        repository.failConvergence(redriveId, failureCode, snapshot, clock.instant());
    }
}
