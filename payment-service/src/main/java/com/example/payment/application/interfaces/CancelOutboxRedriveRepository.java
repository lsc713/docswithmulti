package com.example.payment.application.interfaces;

import com.example.payment.domain.entity.CancelOutboxRedrive;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CancelOutboxRedriveRepository {
    CancelOutboxRedrive createRequested(
        long sourceOutboxId, String requestedBy, String reason, Instant requestedAt);

    List<Long> findRequestedIds(int limit);

    boolean tryStart(long redriveId, Instant startedAt);

    boolean recordPublished(long redriveId, String beforeState, String result);

    boolean failPublish(long redriveId, String lastError, String beforeState, Instant completedAt);

    boolean failConvergence(long redriveId, String lastError, String afterState, Instant completedAt);

    List<CancelOutboxRedrive> findConverging(Instant startedAfter, int limit);

    List<CancelOutboxRedrive> findExpiredUnpublished(Instant cutoff, int limit);

    List<CancelOutboxRedrive> findExpiredPublished(Instant cutoff, int limit);

    boolean resolveAlreadyApplied(long redriveId, String beforeState, String afterState,
                                  String result, Instant completedAt);

    boolean reject(long redriveId, String beforeState, String afterState,
                   String lastError, Instant completedAt);

    boolean resolve(long redriveId, String afterState, Instant completedAt);

    Optional<CancelOutboxRedrive> findById(long redriveId);
}
