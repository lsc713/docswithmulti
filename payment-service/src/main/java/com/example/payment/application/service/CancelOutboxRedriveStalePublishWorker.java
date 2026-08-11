package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureCode;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveStalePublishWorker {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxRedriveTelemetry telemetry;
    private final Clock clock;

    public CancelOutboxRedriveStalePublishWorker(
        CancelOutboxRedriveRepository repository,
        CancelOutboxRedriveTelemetry telemetry,
        Clock clock
    ) {
        this.repository = repository;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public void expire(CancelOutboxRedrive redrive) {
        if (repository.failPublish(
            redrive.getId(),
            "PUBLISH_STATE_UNKNOWN",
            redrive.getBeforeState(),
            clock.instant())) {
            telemetry.terminal(
                redrive,
                CancelOutboxRedriveStatus.FAILED,
                CancelOutboxRedriveFailureStage.PUBLISH,
                CancelOutboxRedriveFailureCode.PUBLISH_STATE_UNKNOWN);
        }
    }
}
