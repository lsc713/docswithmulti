package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CancelOutboxRedriveAuditJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CancelOutboxRedriveAuditJson auditJson;

    @BeforeEach
    void setUp() {
        auditJson = new CancelOutboxRedriveAuditJson(objectMapper);
    }

    @Test
    void inspectionUsesStableExplicitShapeAndRetainsNulls() throws Exception {
        var result = new CancelOutboxInspectionUseCase.Result(
            41L,
            77L,
            CancelOutboxDecision.REDRIVE_REQUIRED,
            null,
            new CancelRestoreLegSnapshot(
                CancelRestoreLegStatus.NOT_APPLIED,
                List.of(new CancelRestoreLegSnapshot.Evidence(19L, "CANCELLED", null, 3))),
            null);

        JsonNode json = objectMapper.readTree(auditJson.inspection(result));

        assertThat(json.propertyNames())
            .containsExactly("decision", "reasonCode", "order", "stock");
        assertThat(json.get("decision").stringValue()).isEqualTo("REDRIVE_REQUIRED");
        assertThat(json.get("reasonCode").isNull()).isTrue();
        assertThat(json.get("order").propertyNames())
            .containsExactly("status", "evidence");
        assertThat(json.at("/order/status").stringValue()).isEqualTo("NOT_APPLIED");
        assertThat(json.at("/order/evidence/0/targetId").longValue()).isEqualTo(19L);
        assertThat(json.at("/order/evidence/0/currentStatus").stringValue()).isEqualTo("CANCELLED");
        assertThat(json.at("/order/evidence/0/actualQuantity").isNull()).isTrue();
        assertThat(json.at("/order/evidence/0/expectedQuantity").intValue()).isEqualTo(3);
        assertThat(json.get("stock").isNull()).isTrue();
        assertThat(json.findValue("payload")).isNull();
        assertThat(json.findValue("paymentKey")).isNull();
        assertThat(json.findValue("outboxId")).isNull();
        assertThat(json.findValue("cancelRequestId")).isNull();
    }

    @Test
    void replayUsesOnlyBrokerAcknowledgementFields() throws Exception {
        String jsonText = auditJson.replay(
            new CancelEventReplayPort.ReplayResult("payment.cancelled", 3, 902L));

        JsonNode json = objectMapper.readTree(jsonText);

        assertThat(json.propertyNames())
            .containsExactly("topic", "partition", "offset");
        assertThat(json.get("topic").stringValue()).isEqualTo("payment.cancelled");
        assertThat(json.get("partition").intValue()).isEqualTo(3);
        assertThat(json.get("offset").longValue()).isEqualTo(902L);
        assertThat(json.findValue("payload")).isNull();
        assertThat(json.findValue("paymentKey")).isNull();
    }

    @Test
    void alreadyAppliedUsesStableOutcomeShape() throws Exception {
        JsonNode json = objectMapper.readTree(auditJson.alreadyAppliedOutcome());

        assertThat(json.propertyNames()).containsExactly("outcome");
        assertThat(json.get("outcome").stringValue()).isEqualTo("ALREADY_APPLIED");
    }

    @Test
    void explicitAuditNamesIgnoreGlobalNamingStrategy() {
        ObjectMapper snakeCaseMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
        var snakeCaseAuditJson = new CancelOutboxRedriveAuditJson(snakeCaseMapper);
        var result = new CancelOutboxInspectionUseCase.Result(
            41L,
            77L,
            CancelOutboxDecision.UNKNOWN,
            CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN,
            new CancelRestoreLegSnapshot(
                CancelRestoreLegStatus.UNKNOWN,
                List.of(new CancelRestoreLegSnapshot.Evidence(19L, "CANCELLED", null, 3))),
            null);

        assertThat(snakeCaseAuditJson.inspection(result)).isEqualTo(
            "{\"decision\":\"UNKNOWN\",\"reasonCode\":\"DOWNSTREAM_UNKNOWN\","
                + "\"order\":{\"status\":\"UNKNOWN\",\"evidence\":[{\"targetId\":19,"
                + "\"currentStatus\":\"CANCELLED\",\"actualQuantity\":null,"
                + "\"expectedQuantity\":3}]},\"stock\":null}");
        assertThat(snakeCaseAuditJson.replay(
            new CancelEventReplayPort.ReplayResult("payment.cancelled", 3, 902L)))
            .isEqualTo("{\"topic\":\"payment.cancelled\",\"partition\":3,\"offset\":902}");
        assertThat(snakeCaseAuditJson.alreadyAppliedOutcome())
            .isEqualTo("{\"outcome\":\"ALREADY_APPLIED\"}");
    }
}
