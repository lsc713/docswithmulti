package com.example.payment.application.service;

import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.exception.InvalidRedriveReasonException;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.usecase.CancelOutboxRedriveQuery;
import com.example.payment.application.usecase.CancelOutboxRedriveUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelOutboxRedriveService implements CancelOutboxRedriveUseCase, CancelOutboxRedriveQuery {

    private final CancelOutboxRedriveRepository repository;
    private final Clock clock;

    public CancelOutboxRedriveService(CancelOutboxRedriveRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CancelOutboxRedrive request(long outboxId, String requestedBy, String reason) {
        validateReason(reason);
        return repository.createRequested(outboxId, requestedBy, reason, clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public CancelOutboxRedrive get(long redriveId) {
        return repository.findById(redriveId)
            .orElseThrow(() -> new CancelOutboxRedriveNotFoundException(redriveId));
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.trim().isEmpty()
            || reason.codePointCount(0, reason.length()) > 500) {
            throw new InvalidRedriveReasonException();
        }
    }
}
