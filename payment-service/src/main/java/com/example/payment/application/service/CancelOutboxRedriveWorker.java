package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxNotFoundException;
import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.exception.CancelEventReplayException;
import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
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
    private final Clock clock;

    public CancelOutboxRedriveWorker(
        CancelOutboxRedriveRepository repository,
        CancelOutboxInspectionUseCase inspection,
        CancelOutboxSourcePort sourcePort,
        CancelEventReplayPort replayPort,
        CancelOutboxRedriveAuditJson auditJson,
        Clock clock
    ) {
        this.repository = repository;
        this.inspection = inspection;
        this.sourcePort = sourcePort;
        this.replayPort = replayPort;
        this.auditJson = auditJson;
        this.clock = clock;
    }

    public void start(long redriveId) {
        if (!repository.tryStart(redriveId, clock.instant())) {
            return;
        }

        var redrive = repository.findById(redriveId)
            .orElseThrow(() -> new CancelOutboxRedriveNotFoundException(redriveId));
        long sourceOutboxId = redrive.getSourceOutboxId();
        var result = inspection.inspect(sourceOutboxId);
        String beforeState = auditJson.inspection(result);

        switch (result.decision()) {
            case ALREADY_APPLIED -> requireWrite(
                repository.resolveAlreadyApplied(
                    redriveId,
                    beforeState,
                    beforeState,
                    auditJson.alreadyAppliedOutcome(),
                    clock.instant()),
                redriveId);
            case NOT_ELIGIBLE -> requireWrite(
                repository.reject(
                    redriveId,
                    beforeState,
                    beforeState,
                    result.reasonCode().name(),
                    clock.instant()),
                redriveId);
            case REDRIVE_REQUIRED -> replay(redriveId, sourceOutboxId, beforeState);
            case UNKNOWN -> failPublish(redriveId, "PREFLIGHT_UNKNOWN", beforeState);
        }
    }

    private void replay(long redriveId, long sourceOutboxId, String beforeState) {
        var source = sourcePort.findById(sourceOutboxId)
            .orElseThrow(() -> new CancelOutboxNotFoundException(sourceOutboxId));
        CancelEventReplayPort.ReplayResult replayResult;
        try {
            replayResult = replayPort.replay(source.cancelRequestId(), source.payload());
        } catch (CancelEventReplayException e) {
            failPublish(redriveId, switch (e.kind()) {
                case TIMEOUT -> "KAFKA_TIMEOUT";
                case SEND_FAILED -> "KAFKA_SEND_FAILED";
            }, beforeState);
            return;
        }
        requireWrite(
            repository.recordPublished(redriveId, beforeState, auditJson.replay(replayResult)),
            redriveId);
    }

    private void failPublish(long redriveId, String failureCode, String beforeState) {
        if (repository.failPublish(redriveId, failureCode, beforeState, clock.instant())) {
            return;
        }
        boolean isTerminal = repository.findById(redriveId)
            .map(redrive -> redrive.getStatus().isTerminal())
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
}
