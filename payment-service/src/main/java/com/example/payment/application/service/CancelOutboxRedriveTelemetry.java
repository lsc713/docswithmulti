package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort.ReplayResult;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureCode;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class CancelOutboxRedriveTelemetry {

    private static final Logger log = LoggerFactory.getLogger(CancelOutboxRedriveTelemetry.class);
    private static final String TERMINAL_COUNTER = "payment.cancel.redrive.terminal.total";
    private static final String EXECUTOR_ACTIVE_GAUGE = "payment.cancel.redrive.executor.active";
    private static final String EXECUTOR_REJECTED_COUNTER =
        "payment.cancel.redrive.executor.rejected.total";

    private final MeterRegistry registry;

    public CancelOutboxRedriveTelemetry(
        MeterRegistry registry,
        @Qualifier("cancelRedriveExecutor") ThreadPoolTaskExecutor executor
    ) {
        this.registry = registry;
        Gauge.builder(EXECUTOR_ACTIVE_GAUGE, executor, ThreadPoolTaskExecutor::getActiveCount)
            .strongReference(true)
            .register(registry);
    }

    public void requested(CancelOutboxRedrive redrive) {
        lifecycleInfo("cancel_redrive_requested", redrive, CancelOutboxRedriveStatus.REQUESTED);
    }

    public void claimed(CancelOutboxRedrive redrive) {
        lifecycleInfo("cancel_redrive_claimed", redrive, CancelOutboxRedriveStatus.REDRIVING);
    }

    public void publishAcked(CancelOutboxRedrive redrive, ReplayResult ack) {
        log.atInfo()
            .addKeyValue("event", "cancel_redrive_publish_acked")
            .addKeyValue("redriveId", redrive.getId())
            .addKeyValue("sourceOutboxId", redrive.getSourceOutboxId())
            .addKeyValue("status", CancelOutboxRedriveStatus.REDRIVING.name())
            .addKeyValue("topic", ack.topic())
            .addKeyValue("partition", ack.partition())
            .addKeyValue("offset", ack.offset())
            .log("Cancel outbox redrive lifecycle event");
    }

    public void terminal(
        CancelOutboxRedrive redrive,
        CancelOutboxRedriveStatus status,
        CancelOutboxRedriveFailureStage stage,
        CancelOutboxRedriveFailureCode code
    ) {
        String event = switch (status) {
            case RESOLVED, RESOLVED_ALREADY_APPLIED -> "cancel_redrive_resolved";
            case REJECTED -> "cancel_redrive_rejected";
            case FAILED -> "cancel_redrive_failed";
            default -> throw new IllegalArgumentException("terminal status required: " + status);
        };
        var builder = status == CancelOutboxRedriveStatus.FAILED ? log.atWarn() : log.atInfo();
        builder
            .addKeyValue("event", event)
            .addKeyValue("redriveId", redrive.getId())
            .addKeyValue("sourceOutboxId", redrive.getSourceOutboxId())
            .addKeyValue("status", status.name());
        if (stage != null) {
            builder.addKeyValue("failureStage", stage.name());
        }
        if (code != null) {
            builder.addKeyValue("errorCode", code.name());
        }
        builder.log("Cancel outbox redrive lifecycle event");

        registry.counter(
            TERMINAL_COUNTER,
            "status", status.name(),
            "failure_stage", stage == null ? "none" : stage.name())
            .increment();
    }

    public void executorRejected() {
        registry.counter(EXECUTOR_REJECTED_COUNTER).increment();
    }

    private void lifecycleInfo(
        String event,
        CancelOutboxRedrive redrive,
        CancelOutboxRedriveStatus status
    ) {
        log.atInfo()
            .addKeyValue("event", event)
            .addKeyValue("redriveId", redrive.getId())
            .addKeyValue("sourceOutboxId", redrive.getSourceOutboxId())
            .addKeyValue("status", status.name())
            .log("Cancel outbox redrive lifecycle event");
    }
}
