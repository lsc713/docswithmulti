package com.example.payment.application.service;

import com.example.payment.application.exception.CancelEventReplayException;
import com.example.payment.application.exception.CancelOutboxRedriveNotFoundException;
import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.interfaces.CancelOutboxSourcePort;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureCode;
import com.example.payment.domain.entity.CancelOutboxRedriveFailureStage;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import com.example.payment.domain.entity.CancelStatus;
import com.example.payment.domain.entity.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveWorkerTest {

    private static final long REDRIVE_ID = 7L;
    private static final long SOURCE_OUTBOX_ID = 41L;
    private static final long CANCEL_REQUEST_ID = 77L;
    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");
    private static final String INSPECTION_JSON = "{\"decision\":\"ALREADY_APPLIED\","
        + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";

    private CancelOutboxRedriveRepository repository;
    private CancelOutboxInspectionUseCase inspection;
    private CancelOutboxSourcePort sourcePort;
    private CancelEventReplayPort replayPort;
    private CancelOutboxRedriveTelemetry telemetry;
    private CancelOutboxRedriveWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(CancelOutboxRedriveRepository.class);
        inspection = mock(CancelOutboxInspectionUseCase.class);
        sourcePort = mock(CancelOutboxSourcePort.class);
        replayPort = mock(CancelEventReplayPort.class);
        telemetry = mock(CancelOutboxRedriveTelemetry.class);
        worker = new CancelOutboxRedriveWorker(
            repository,
            inspection,
            sourcePort,
            replayPort,
            new CancelOutboxRedriveAuditJson(new ObjectMapper()),
            telemetry,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void casLoserReturnsWithoutAnyObservableWork() {
        when(repository.tryStart(REDRIVE_ID, NOW)).thenReturn(false);

        worker.start(REDRIVE_ID);

        verify(repository).tryStart(REDRIVE_ID, NOW);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(inspection, sourcePort, replayPort, telemetry);
    }

    @Test
    void alreadyAppliedResolvesWithoutReplayUsingIdenticalSnapshots() {
        var result = inspectionResult(CancelOutboxDecision.ALREADY_APPLIED, null);
        stubStarted(result);
        when(repository.resolveAlreadyApplied(
            REDRIVE_ID,
            INSPECTION_JSON,
            INSPECTION_JSON,
            "{\"outcome\":\"ALREADY_APPLIED\"}",
            NOW)).thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).resolveAlreadyApplied(
            REDRIVE_ID,
            INSPECTION_JSON,
            INSPECTION_JSON,
            "{\"outcome\":\"ALREADY_APPLIED\"}",
            NOW);
        verify(telemetry).claimed(org.mockito.ArgumentMatchers.any(CancelOutboxRedrive.class));
        verify(telemetry).terminal(
            org.mockito.ArgumentMatchers.any(CancelOutboxRedrive.class),
            org.mockito.ArgumentMatchers.eq(CancelOutboxRedriveStatus.RESOLVED_ALREADY_APPLIED),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
        verifyNoInteractions(sourcePort, replayPort);
        verify(repository, never()).recordPublished(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void notEligibleRejectsWithoutReplayUsingReasonCodeAndIdenticalSnapshots() {
        var result = inspectionResult(
            CancelOutboxDecision.NOT_ELIGIBLE,
            CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE);
        String json = "{\"decision\":\"NOT_ELIGIBLE\","
            + "\"reasonCode\":\"INCONSISTENT_DOWNSTREAM_STATE\","
            + "\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
        stubStarted(result);
        when(repository.reject(
            REDRIVE_ID,
            json,
            json,
            "INCONSISTENT_DOWNSTREAM_STATE",
            NOW)).thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(repository).reject(
            REDRIVE_ID,
            json,
            json,
            "INCONSISTENT_DOWNSTREAM_STATE",
            NOW);
        verify(telemetry).terminal(
            org.mockito.ArgumentMatchers.any(CancelOutboxRedrive.class),
            org.mockito.ArgumentMatchers.eq(CancelOutboxRedriveStatus.REJECTED),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(
                CancelOutboxRedriveFailureCode.INCONSISTENT_DOWNSTREAM_STATE));
        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void redriveRequiredLoadsSourceByAggregateIdAndReplaysRawMessageExactlyOnce() {
        var result = inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null);
        String inspectionJson = "{\"decision\":\"REDRIVE_REQUIRED\","
            + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
        String payload = "{\"cancelRequestId\":77,\"items\":[{\"productId\":9}]}";
        var source = new CancelOutboxSourcePort.SourceSnapshot(
            SOURCE_OUTBOX_ID,
            CANCEL_REQUEST_ID,
            payload,
            "DEAD",
            CancelStatus.COMPLETED,
            PaymentStatus.CANCELLED);
        var ack = new CancelEventReplayPort.ReplayResult("payment.cancelled", 3, 902L);
        stubStarted(result);
        when(sourcePort.findById(SOURCE_OUTBOX_ID)).thenReturn(Optional.of(source));
        when(replayPort.replay(CANCEL_REQUEST_ID, payload)).thenReturn(ack);
        when(repository.recordPublished(
            REDRIVE_ID,
            inspectionJson,
            "{\"topic\":\"payment.cancelled\",\"partition\":3,\"offset\":902}"))
            .thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(sourcePort).findById(SOURCE_OUTBOX_ID);
        verify(sourcePort, never()).findById(REDRIVE_ID);
        verify(replayPort).replay(CANCEL_REQUEST_ID, payload);
        verifyNoMoreInteractions(replayPort);
        verify(repository, never()).failPublish(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
        verify(repository).recordPublished(
            REDRIVE_ID,
            inspectionJson,
            "{\"topic\":\"payment.cancelled\",\"partition\":3,\"offset\":902}");
        verify(telemetry).publishAcked(
            org.mockito.ArgumentMatchers.any(CancelOutboxRedrive.class),
            org.mockito.ArgumentMatchers.eq(ack));
    }

    @Test
    void unknownFailsPreflightWithoutReplayOrPublishedWrite() {
        var result = inspectionResult(
            CancelOutboxDecision.UNKNOWN,
            CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN);
        stubStarted(result);
        String unknownJson = "{\"decision\":\"UNKNOWN\","
            + "\"reasonCode\":\"DOWNSTREAM_UNKNOWN\",\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
        when(repository.failPublish(
            REDRIVE_ID, "PREFLIGHT_UNKNOWN", unknownJson, NOW)).thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(repository).failPublish(REDRIVE_ID, "PREFLIGHT_UNKNOWN", unknownJson, NOW);
        verify(telemetry).terminal(
            org.mockito.ArgumentMatchers.any(CancelOutboxRedrive.class),
            org.mockito.ArgumentMatchers.eq(CancelOutboxRedriveStatus.FAILED),
            org.mockito.ArgumentMatchers.eq(CancelOutboxRedriveFailureStage.PUBLISH),
            org.mockito.ArgumentMatchers.eq(CancelOutboxRedriveFailureCode.PREFLIGHT_UNKNOWN));
        verifyNoInteractions(sourcePort, replayPort);
        verify(repository, never()).recordPublished(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingAggregateAfterClaimPropagatesNotFoundWithoutTerminalWrite() {
        when(repository.tryStart(REDRIVE_ID, NOW)).thenReturn(true);
        when(repository.findById(REDRIVE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(CancelOutboxRedriveNotFoundException.class);

        verify(repository).tryStart(REDRIVE_ID, NOW);
        verify(repository).findById(REDRIVE_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(inspection, sourcePort, replayPort);
    }

    @Test
    void inspectionExceptionPropagatesWithoutTerminalWrite() {
        when(repository.tryStart(REDRIVE_ID, NOW)).thenReturn(true);
        when(repository.findById(REDRIVE_ID)).thenReturn(Optional.of(redriving()));
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenThrow(new IllegalStateException("inspection down"));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("inspection down");

        verify(repository).tryStart(REDRIVE_ID, NOW);
        verify(repository).findById(REDRIVE_ID);
        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void sourceExceptionPropagatesWithoutTerminalWriteOrReplay() {
        stubStarted(inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null));
        when(sourcePort.findById(SOURCE_OUTBOX_ID))
            .thenThrow(new IllegalStateException("source down"));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("source down");

        verify(repository).tryStart(REDRIVE_ID, NOW);
        verify(repository).findById(REDRIVE_ID);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(replayPort);
    }

    @Test
    void unexpectedReplayExceptionPropagatesAfterExactlyOneAttemptWithoutTerminalWrite() {
        String payload = "{\"cancelRequestId\":77}";
        stubStarted(inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null));
        when(sourcePort.findById(SOURCE_OUTBOX_ID)).thenReturn(Optional.of(
            new CancelOutboxSourcePort.SourceSnapshot(
                SOURCE_OUTBOX_ID,
                CANCEL_REQUEST_ID,
                payload,
                "DEAD",
                CancelStatus.COMPLETED,
                PaymentStatus.CANCELLED)));
        when(replayPort.replay(CANCEL_REQUEST_ID, payload))
            .thenThrow(new IllegalStateException("broker down"));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("broker down");

        verify(repository).tryStart(REDRIVE_ID, NOW);
        verify(repository).findById(REDRIVE_ID);
        verify(replayPort).replay(CANCEL_REQUEST_ID, payload);
        verifyNoMoreInteractions(replayPort);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void replayTimeoutFailsPublishUsingPreflightSnapshot() {
        String payload = "{\"cancelRequestId\":77}";
        String inspectionJson = "{\"decision\":\"REDRIVE_REQUIRED\","
            + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
        stubStarted(inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null));
        when(sourcePort.findById(SOURCE_OUTBOX_ID)).thenReturn(Optional.of(source(payload)));
        when(replayPort.replay(CANCEL_REQUEST_ID, payload)).thenThrow(
            new CancelEventReplayException(CANCEL_REQUEST_ID, CancelEventReplayException.Kind.TIMEOUT,
                new java.util.concurrent.TimeoutException("broker detail")));
        when(repository.failPublish(REDRIVE_ID, "KAFKA_TIMEOUT", inspectionJson, NOW)).thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(repository).failPublish(REDRIVE_ID, "KAFKA_TIMEOUT", inspectionJson, NOW);
        verify(repository, never()).recordPublished(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void replaySendFailureFailsPublishUsingPreflightSnapshot() {
        String payload = "{\"cancelRequestId\":77}";
        String inspectionJson = "{\"decision\":\"REDRIVE_REQUIRED\","
            + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
        stubStarted(inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null));
        when(sourcePort.findById(SOURCE_OUTBOX_ID)).thenReturn(Optional.of(source(payload)));
        when(replayPort.replay(CANCEL_REQUEST_ID, payload)).thenThrow(
            new CancelEventReplayException(CANCEL_REQUEST_ID, CancelEventReplayException.Kind.SEND_FAILED,
                new IllegalStateException("broker detail")));
        when(repository.failPublish(REDRIVE_ID, "KAFKA_SEND_FAILED", inspectionJson, NOW)).thenReturn(true);

        worker.start(REDRIVE_ID);

        verify(repository).failPublish(REDRIVE_ID, "KAFKA_SEND_FAILED", inspectionJson, NOW);
        verify(repository, never()).recordPublished(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void preflightFailureCasLoserIsBenignWhenReloadedRowIsTerminal() {
        var result = inspectionResult(
            CancelOutboxDecision.UNKNOWN,
            CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN);
        when(repository.tryStart(REDRIVE_ID, NOW)).thenReturn(true);
        when(repository.findById(REDRIVE_ID))
            .thenReturn(Optional.of(redriving()))
            .thenReturn(Optional.of(terminal()));
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result);

        worker.start(REDRIVE_ID);

        verify(repository).failPublish(
            REDRIVE_ID,
            "PREFLIGHT_UNKNOWN",
            "{\"decision\":\"UNKNOWN\",\"reasonCode\":\"DOWNSTREAM_UNKNOWN\",\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}",
            NOW);
        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void preflightFailureCasLoserRaisesSafeStateExceptionWhenReloadedRowIsActive() {
        var result = inspectionResult(
            CancelOutboxDecision.UNKNOWN,
            CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN);
        stubStarted(result);

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Unexpected cancel outbox redrive state: 7");

        verify(repository, org.mockito.Mockito.times(2)).findById(REDRIVE_ID);
        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void alreadyAppliedConditionalWriteFailureIsSurfaced() {
        stubStarted(inspectionResult(CancelOutboxDecision.ALREADY_APPLIED, null));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("7");

        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void rejectedConditionalWriteFailureIsSurfaced() {
        stubStarted(inspectionResult(
            CancelOutboxDecision.NOT_ELIGIBLE,
            CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("7");

        verifyNoInteractions(sourcePort, replayPort);
    }

    @Test
    void brokerAckWithPublishedWriteFailureRaisesSafeStateWithoutFailureWriteOrSecondReplay() {
        String payload = "{\"cancelRequestId\":77}";
        stubStarted(inspectionResult(CancelOutboxDecision.REDRIVE_REQUIRED, null));
        when(sourcePort.findById(SOURCE_OUTBOX_ID)).thenReturn(Optional.of(
            new CancelOutboxSourcePort.SourceSnapshot(
                SOURCE_OUTBOX_ID,
                CANCEL_REQUEST_ID,
                payload,
                "DEAD",
                CancelStatus.COMPLETED,
                PaymentStatus.CANCELLED)));
        when(replayPort.replay(CANCEL_REQUEST_ID, payload))
            .thenReturn(new CancelEventReplayPort.ReplayResult("payment.cancelled", 3, 902L));

        assertThatThrownBy(() -> worker.start(REDRIVE_ID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("7");

        verify(replayPort).replay(CANCEL_REQUEST_ID, payload);
        verifyNoMoreInteractions(replayPort);
        verify(repository, never()).failPublish(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    private void stubStarted(CancelOutboxInspectionUseCase.Result result) {
        when(repository.tryStart(REDRIVE_ID, NOW)).thenReturn(true);
        when(repository.findById(REDRIVE_ID)).thenReturn(Optional.of(redriving()));
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result);
    }

    private CancelOutboxInspectionUseCase.Result inspectionResult(
        CancelOutboxDecision decision,
        CancelOutboxReasonCode reasonCode
    ) {
        return new CancelOutboxInspectionUseCase.Result(
            SOURCE_OUTBOX_ID,
            CANCEL_REQUEST_ID,
            decision,
            reasonCode,
            new CancelRestoreLegSnapshot(CancelRestoreLegStatus.APPLIED, List.of()),
            new CancelRestoreLegSnapshot(CancelRestoreLegStatus.APPLIED, List.of()));
    }

    private CancelOutboxRedrive redriving() {
        return CancelOutboxRedrive.reconstitute(
            REDRIVE_ID,
            SOURCE_OUTBOX_ID,
            CancelOutboxRedriveStatus.REDRIVING,
            null,
            "operator-1",
            "recover dead outbox",
            NOW.minusSeconds(60),
            NOW,
            null,
            null,
            null,
            null,
            null);
    }

    private CancelOutboxRedrive terminal() {
        return CancelOutboxRedrive.reconstitute(
            REDRIVE_ID,
            SOURCE_OUTBOX_ID,
            CancelOutboxRedriveStatus.FAILED,
            null,
            "operator-1",
            "recover dead outbox",
            NOW.minusSeconds(60),
            NOW,
            NOW,
            null,
            "PREFLIGHT_UNKNOWN",
            "{}",
            null);
    }

    private CancelOutboxSourcePort.SourceSnapshot source(String payload) {
        return new CancelOutboxSourcePort.SourceSnapshot(
            SOURCE_OUTBOX_ID,
            CANCEL_REQUEST_ID,
            payload,
            "DEAD",
            CancelStatus.COMPLETED,
            PaymentStatus.CANCELLED);
    }
}
