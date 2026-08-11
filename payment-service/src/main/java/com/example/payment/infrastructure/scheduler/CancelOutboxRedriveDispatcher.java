package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveDispatcher {

    private final CancelOutboxRedriveRepository repository;
    private final CancelOutboxRedriveWorker worker;
    private final int batchSize;

    public CancelOutboxRedriveDispatcher(
        CancelOutboxRedriveRepository repository,
        CancelOutboxRedriveWorker worker,
        @Value("${cancel.redrive.batch-size:100}") int batchSize
    ) {
        this.repository = repository;
        this.worker = worker;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${cancel.redrive.dispatch-ms:1000}")
    public void dispatch() {
        for (long redriveId : repository.findRequestedIds(batchSize)) {
            try {
                worker.start(redriveId);
            } catch (Exception ignored) {
                log.warn("Cancel outbox redrive dispatch failed; redriveId={}", redriveId);
            }
        }
    }
}
