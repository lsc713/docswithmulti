package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveDeadlineWorker;
import com.example.payment.application.service.CancelOutboxRedriveStalePublishWorker;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveRecoveryPoller {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxRedriveStalePublishWorker stalePublishWorker;
    private final CancelOutboxRedriveDeadlineWorker deadlineWorker;
    private final CancelOutboxRedriveTaskExecutor executor;
    private final Clock clock;
    private final long observationSeconds;
    private final int batchSize;

    public CancelOutboxRedriveRecoveryPoller(
        CancelOutboxRedriveRepository repository,
        CancelOutboxRedriveStalePublishWorker stalePublishWorker,
        CancelOutboxRedriveDeadlineWorker deadlineWorker,
        CancelOutboxRedriveTaskExecutor executor,
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
        this.stalePublishWorker = stalePublishWorker;
        this.deadlineWorker = deadlineWorker;
        this.executor = executor;
        this.clock = clock;
        this.observationSeconds = observationSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(
        fixedDelayString = "${cancel.redrive.recovery-ms:2000}",
        initialDelayString = "${cancel.redrive.recovery-initial-delay-ms:2000}")
    public void poll() {
        var cutoff = clock.instant().minusSeconds(observationSeconds);
        for (var redrive : repository.findExpiredUnpublished(cutoff, batchSize)) {
            executor.tryExecute(() -> expireStalePublish(redrive));
        }
        for (var redrive : repository.findExpiredPublished(cutoff, batchSize)) {
            executor.tryExecute(() -> checkDeadline(redrive));
        }
    }

    private void expireStalePublish(CancelOutboxRedrive redrive) {
        try {
            stalePublishWorker.expire(redrive);
        } catch (Exception exception) {
            log.warn(
                "CANCEL_REDRIVE_STALE_PUBLISH_ITEM_FAILED redriveId={} exceptionType={}",
                redrive.getId(),
                exception.getClass().getSimpleName());
        }
    }

    private void checkDeadline(CancelOutboxRedrive redrive) {
        try {
            deadlineWorker.check(redrive);
        } catch (Exception exception) {
            log.warn(
                "CANCEL_REDRIVE_DEADLINE_ITEM_FAILED redriveId={} exceptionType={}",
                redrive.getId(),
                exception.getClass().getSimpleName());
        }
    }
}
