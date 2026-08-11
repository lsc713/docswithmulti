package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveConvergenceWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveConvergencePoller {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxRedriveConvergenceWorker worker;
    private final Clock clock;
    private final long observationSeconds;
    private final int batchSize;

    public CancelOutboxRedriveConvergencePoller(
        CancelOutboxRedriveRepository repository,
        CancelOutboxRedriveConvergenceWorker worker,
        Clock clock,
        @Value("${cancel.redrive.observation-seconds:60}") long observationSeconds,
        @Value("${cancel.redrive.batch-size:100}") int batchSize
    ) {
        if (observationSeconds <= 0) {
            throw new IllegalArgumentException("observationSeconds must be greater than 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than 0");
        }
        this.repository = repository;
        this.worker = worker;
        this.clock = clock;
        this.observationSeconds = observationSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${cancel.redrive.convergence-ms:2000}",
        initialDelayString = "${cancel.redrive.convergence-initial-delay-ms:2000}")
    public void poll() {
        var startedAfter = clock.instant().minusSeconds(observationSeconds);
        for (var redrive : repository.findConverging(startedAfter, batchSize)) {
            try {
                worker.check(redrive);
            } catch (Exception exception) {
                log.warn(
                    "CANCEL_REDRIVE_CONVERGENCE_ITEM_FAILED redriveId={} exceptionType={}",
                    redrive.getId(),
                    exception.getClass().getSimpleName());
            }
        }
    }
}
