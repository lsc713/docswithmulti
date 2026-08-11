package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to encode cancel outbox redrive audit JSON", e);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"decision", "reasonCode", "order", "stock"})
    private record InspectionAudit(
        @JsonProperty("decision") String decision,
        @JsonProperty("reasonCode") String reasonCode,
        @JsonProperty("order") LegAudit order,
        @JsonProperty("stock") LegAudit stock
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"status", "evidence"})
    private record LegAudit(
        @JsonProperty("status") String status,
        @JsonProperty("evidence") List<EvidenceAudit> evidence
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"targetId", "currentStatus", "actualQuantity", "expectedQuantity"})
    private record EvidenceAudit(
        @JsonProperty("targetId") long targetId,
        @JsonProperty("currentStatus") String currentStatus,
        @JsonProperty("actualQuantity") Integer actualQuantity,
        @JsonProperty("expectedQuantity") Integer expectedQuantity
    ) {}

    @JsonPropertyOrder({"topic", "partition", "offset"})
    private record ReplayAudit(
        @JsonProperty("topic") String topic,
        @JsonProperty("partition") int partition,
        @JsonProperty("offset") long offset
    ) {}

    private record OutcomeAudit(@JsonProperty("outcome") String outcome) {}
}
