package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.exception.CancelEventReplayException;
import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
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
public class CancelOutboxRedriveWorker {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxInspectionUseCase inspection;
    private final CancelOutboxSourcePort sourcePort;
    private final CancelEventReplayPort replayPort;
    private final CancelOutboxRedriveAuditJson auditJson;
    private final CancelOutboxRedriveTelemetry telemetry;
    private final Clock clock;

    public CancelOutboxRedriveWorker(
        CancelOutboxRedriveRepository repository,
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxSourcePort sourcePort,
        CancelEventReplayPort replayPort,
        CancelOutboxRedriveAuditJson auditJson,
        CancelOutboxRedriveTelemetry telemetry,
        Clock clock
    ) {
        this.repository = repository;
        this.inspection = inspection;
        this.sourcePort = sourcePort;
        this.replayPort = replayPort;
        this.auditJson = auditJson;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public void start(long redriveId) {
        if (!repository.tryStart(redriveId, clock.instant())) {
            return;
        }

        var redrive = repository.findById(redriveId)
            .orElseThrow(() -> new CancelOutboxRedriveNotFoundException(redriveId));
        telemetry.claimed(redrive);
        long sourceOutboxId = redrive.getSourceOutboxId();
        var result = inspection.inspect(sourceOutboxId);
        String beforeState = auditJson.inspection(result);

        switch (result.decision()) {
            case ALREADY_APPLIED -> requireTerminalWrite(
                repository.resolveAlreadyApplied(
                    redriveId,
                    beforeState,
                    beforeState,
                    auditJson.alreadyAppliedOutcome(),
                    clock.instant()),
                redrive,
                CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED,
                null,
                null);
            case NOT_ELIGIBLE -> requireTerminalWrite(
                repository.reject(
                    redriveId,
                    beforeState,
                    beforeState,
                    result.reasonCode().name(),
                    clock.instant()),
                redrive,
                CancelOutboxRedriveStatus.REJECTED,
                null,
                CancelOutboxRedriveFailureCode.valueOf(result.reasonCode().name()));
            case REDRIVE_REQUIRED -> replay(redrive, beforeState);
            case UNKNOWN -> failPublish(
                redrive, CancelOutboxRedriveFailureCode.PREFLIGHT_UNKNOWN, beforeState);
        }
    }

    private void replay(CancelOutboxRedrive redrive, String beforeState) {
        long redriveId = redrive.getId();
        var source = sourcePort.findById(redrive.getSourceOutboxId())
            .orElseThrow(() -> new CancelOutboxNotFoundException(redrive.getSourceOutboxId()));
        CancelEventReplayPort.ReplayResult replayResult;
        try {
            replayResult = replayPort.replay(source.cancelRequestId(), source.payload());
        } catch (CancelEventReplayException e) {
            failPublish(redrive, switch (e.kind()) {
                case TIMEOUT -> CancelOutboxRedriveFailureCode.KAFKA_TIMEOUT;
                case SEND_FAILED -> CancelOutboxRedriveFailureCode.KAFKA_SEND_FAILED;
            }, beforeState);
            return;
        }
        requireWrite(repository.recordPublished(
            redriveId, beforeState, auditJson.replay(replayResult)), redriveId);
        telemetry.publishAcked(redrive, replayResult);
    }

    private void failPublish(
        CancelOutboxRedrive redrive,
        CancelOutboxRedriveFailureCode failureCode,
        String beforeState
    ) {
        long redriveId = redrive.getId();
        if (repository.failPublish(
            redriveId, failureCode.name(), beforeState, clock.instant())) {
            telemetry.terminal(
                redrive,
                CancelOutboxRedriveStatus.FAILED,
                CancelOutboxRedriveFailureStage.PUBLISH,
                failureCode);
            return;
        }
        boolean isTerminal = repository.findById(redriveId)
            .map(current -> current.getStatus().isTerminal())
            .orElse(false);
        if (!isTerminal) {
            throw new IllegalStateException("Unexpected cancel outbox redrive state: " + redriveId);
        }
    }

    private void requireWrite(boolean written, long redriveId) {
        if (!written) {
            throw new IllegalStateException("Unexpected cancel outbox redrive state: " + redriveId);
        }
    }

    private void requireTerminalWrite(
        boolean written,
        CancelOutboxRedrive redrive,
        CancelOutboxRedriveStatus status,
        CancelOutboxRedriveFailureStage stage,
        CancelOutboxRedriveFailureCode code
    ) {
        requireWrite(written, redrive.getId());
        telemetry.terminal(redrive, status, stage, code);
    }
}
