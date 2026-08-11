package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CancelOutboxRedriveResponse(
    long redriveId,
    long sourceOutboxId,
    String status,
    String failureStage,
    String requestedBy,
    String reason,
    String requestedAt,
    String startedAt,
    String completedAt,
    String result,
    String lastError,
    String beforeState,
    String afterState
) {
    public static CancelOutboxRedriveResponse from(CancelOutboxRedrive redrive) {
        return new CancelOutboxRedriveResponse(
            redrive.getId(),
            redrive.getSourceOutboxId(),
            redrive.getStatus().name(),
            redrive.getFailureStage() == null ? null : redrive.getFailureStage().name(),
            redrive.getRequestedBy(),
            redrive.getReason(),
            asText(redrive.getRequestedAt()),
            asText(redrive.getStartedAt()),
            asText(redrive.getCompletedAt()),
            redrive.getResult(),
            redrive.getLastError(),
            redrive.getBeforeState(),
            redrive.getAfterState());
    }

    private static String asText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
