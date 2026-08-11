package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelOutboxRedriveStalePublishWorker {

    private final CancelOutboxRedriveRepository repository;
    private final Clock clock;

    public CancelOutboxRedriveStalePublishWorker(CancelOutboxRedriveRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void expire(CancelOutboxRedrive redrive) {
        repository.failPublish(
            redrive.getId(),
            "PUBLISH_STATE_UNKNOWN",
            redrive.getBeforeState(),
            clock.instant());
    }
}
