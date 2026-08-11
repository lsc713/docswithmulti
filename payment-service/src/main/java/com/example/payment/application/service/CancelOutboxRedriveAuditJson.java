package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CancelOutboxRedriveAuditJson {

    private final ObjectMapper objectMapper;

    public CancelOutboxRedriveAuditJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String inspection(CancelOutboxInspectionUseCase.Result result) {
        return write(new InspectionAudit(
            result.decision().name(),
            result.reasonCode() == null ? null : result.reasonCode().name(),
            leg(result.order()),
            leg(result.stock())));
    }

    public String replay(CancelEventReplayPort.ReplayResult result) {
        return write(new ReplayAudit(result.topic(), result.partition(), result.offset()));
    }

    public String alreadyAppliedOutcome() {
        return write(new OutcomeAudit("ALREADY_APPLIED"));
    }

    private LegAudit leg(CancelRestoreLegSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new LegAudit(
            snapshot.status().name(),
            snapshot.evidence().stream().map(this::evidence).toList());
    }

    private EvidenceAudit evidence(CancelRestoreLegSnapshot.Evidence evidence) {
        return new EvidenceAudit(
            evidence.targetId(),
            evidence.currentStatus(),
            evidence.actualQuantity(),
            evidence.expectedQuantity());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cancel outbox redrive audit JSON", e);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"decision", "reasonCode", "order", "stock"})
    private record InspectionAudit(
        String decision,
        String reasonCode,
        LegAudit order,
        LegAudit stock
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"status", "evidence"})
    private record LegAudit(String status, List<EvidenceAudit> evidence) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"targetId", "currentStatus", "actualQuantity", "expectedQuantity"})
    private record EvidenceAudit(
        long targetId,
        String currentStatus,
        Integer actualQuantity,
        Integer expectedQuantity
    ) {}

    @JsonPropertyOrder({"topic", "partition", "offset"})
    private record ReplayAudit(String topic, int partition, long offset) {}

    private record OutcomeAudit(String outcome) {}
}
