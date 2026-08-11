package com.example.payment.presentation.dto;

import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    JsonNode result,
    String lastError,
    JsonNode beforeState,
    JsonNode afterState
) {
    public static CancelOutboxRedriveResponse from(
        CancelOutboxRedrive redrive,
        ObjectMapper objectMapper
    ) {
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
            parse(redrive.getResult(), "result", objectMapper),
            redrive.getLastError(),
            parse(redrive.getBeforeState(), "beforeState", objectMapper),
            parse(redrive.getAfterState(), "afterState", objectMapper));
    }

    private static JsonNode parse(String value, String field, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        final JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Corrupt cancel outbox redrive " + field + " JSON", exception);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Corrupt cancel outbox redrive " + field + " JSON");
        }
        return node;
    }

    private static String asText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
