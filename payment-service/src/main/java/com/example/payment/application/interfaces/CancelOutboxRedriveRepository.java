package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelOutboxRedrive;
import java.time.Instant;
import java.util.Optional;

public interface CancelOutboxRedriveRepository {
    CancelOutboxRedrive createRequested(
        long sourceOutboxId, String requestedBy, String reason, Instant requestedAt);

    Optional<CancelOutboxRedrive> findById(long redriveId);
}
