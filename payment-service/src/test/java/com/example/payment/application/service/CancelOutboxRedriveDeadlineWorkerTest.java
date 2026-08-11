package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.model.CancelOutboxDecision;
import com.example.payment.application.model.CancelOutboxReasonCode;
import com.example.payment.application.model.CancelRestoreLegSnapshot;
import com.example.payment.application.model.CancelRestoreLegStatus;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveDeadlineWorkerTest {

    private static final long REDRIVE_ID = 7L;
    private static final long SOURCE_OUTBOX_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");
    private static final String ALREADY_APPLIED_SNAPSHOT = "{\"decision\":\"ALREADY_APPLIED\","
        + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";
    private static final String REDRIVE_REQUIRED_SNAPSHOT = "{\"decision\":\"REDRIVE_REQUIRED\","
        + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"NOT_APPLIED\",\"evidence\":[]}}";
    private static final String UNKNOWN_SNAPSHOT = "{\"decision\":\"UNKNOWN\","
        + "\"reasonCode\":\"DOWNSTREAM_UNKNOWN\",\"order\":{\"status\":\"UNKNOWN\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"UNKNOWN\",\"evidence\":[]}}";
    private static final String NOT_ELIGIBLE_SNAPSHOT = "{\"decision\":\"NOT_ELIGIBLE\","
        + "\"reasonCode\":\"INCONSISTENT_DOWNSTREAM_STATE\",\"order\":{\"status\":\"INCONSISTENT\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";

    private CancelOutboxRedriveRepository repository;
    private CancelOutboxInspectionUseCase inspection;
    private CancelOutboxRedriveDeadlineWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(CancelOutboxRedriveRepository.class);
        inspection = mock(CancelOutboxInspectionUseCase.class);
        worker = new CancelOutboxRedriveDeadlineWorker(
            repository,
            inspection,
            new CancelOutboxRedriveAuditJson(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void alreadyAppliedResolvesWithFinalInspectionSnapshot() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
            CancelOutboxDecision.ALREADY_APPLIED, null,
            CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED));
        when(repository.resolve(REDRIVE_ID, ALREADY_APPLIED_SNAPSHOT, NOW)).thenReturn(true);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).resolve(REDRIVE_ID, ALREADY_APPLIED_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void redriveRequiredFailsWithConvergenceTimeout() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
            CancelOutboxDecision.REDRIVE_REQUIRED, null,
            CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.NOT_APPLIED));
        when(repository.failConvergence(
            REDRIVE_ID, "CONVERGENCE_TIMEOUT", REDRIVE_REQUIRED_SNAPSHOT, NOW)).thenReturn(true);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).failConvergence(
            REDRIVE_ID, "CONVERGENCE_TIMEOUT", REDRIVE_REQUIRED_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void unknownFailsWithDownstreamUnknown() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
            CancelOutboxDecision.UNKNOWN, CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN,
            CancelRestoreLegStatus.UNKNOWN, CancelRestoreLegStatus.UNKNOWN));
        when(repository.failConvergence(
            REDRIVE_ID, "DOWNSTREAM_UNKNOWN", UNKNOWN_SNAPSHOT, NOW)).thenReturn(true);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).failConvergence(REDRIVE_ID, "DOWNSTREAM_UNKNOWN", UNKNOWN_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void inconsistentNotEligibleStoresItsExactReasonCode() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
            CancelOutboxDecision.NOT_ELIGIBLE, CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE,
            CancelRestoreLegStatus.INCONSISTENT, CancelRestoreLegStatus.APPLIED));
        when(repository.failConvergence(
            REDRIVE_ID, "INCONSISTENT_DOWNSTREAM_STATE", NOT_ELIGIBLE_SNAPSHOT, NOW)).thenReturn(true);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).failConvergence(
            REDRIVE_ID, "INCONSISTENT_DOWNSTREAM_STATE", NOT_ELIGIBLE_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void inspectionFailureStoresSafeUnknownSnapshotWithoutExceptionText() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenThrow(new IllegalStateException("sensitive downstream detail"));
        String safeSnapshot = "{\"decision\":\"UNKNOWN\",\"reasonCode\":\"DOWNSTREAM_UNKNOWN\","
            + "\"order\":{\"status\":\"UNKNOWN\",\"evidence\":[]},"
            + "\"stock\":{\"status\":\"UNKNOWN\",\"evidence\":[]}}";
        when(repository.failConvergence(REDRIVE_ID, "DOWNSTREAM_UNKNOWN", safeSnapshot, NOW)).thenReturn(true);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).failConvergence(REDRIVE_ID, "DOWNSTREAM_UNKNOWN", safeSnapshot, NOW);
        verifyNoMoreInteractions(repository);
        assertThat(safeSnapshot).doesNotContain("sensitive downstream detail");
    }

    @Test
    void falseTerminalCasIsBenignAndDoesNotRetryInspection() {
        when(inspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
            CancelOutboxDecision.REDRIVE_REQUIRED, null,
            CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.NOT_APPLIED));
        when(repository.failConvergence(
            REDRIVE_ID, "CONVERGENCE_TIMEOUT", REDRIVE_REQUIRED_SNAPSHOT, NOW)).thenReturn(false);

        worker.check(redriving());

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).failConvergence(
            REDRIVE_ID, "CONVERGENCE_TIMEOUT", REDRIVE_REQUIRED_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void constructorHasNoReplayPortDependency() {
        assertThat(Arrays.stream(CancelOutboxRedriveDeadlineWorker.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
            .doesNotContain(CancelEventReplayPort.class);
    }

    private CancelOutboxInspectionUseCase.Result result(
        CancelOutboxDecision decision,
        CancelOutboxReasonCode reasonCode,
        CancelRestoreLegStatus orderStatus,
        CancelRestoreLegStatus stockStatus
    ) {
        return new CancelOutboxInspectionUseCase.Result(
            SOURCE_OUTBOX_ID,
            77L,
            decision,
            reasonCode,
            new CancelRestoreLegSnapshot(orderStatus, List.of()),
            new CancelRestoreLegSnapshot(stockStatus, List.of()));
    }

    private CancelOutboxRedrive redriving() {
        return CancelOutboxRedrive.reconstitute(
            REDRIVE_ID, SOURCE_OUTBOX_ID, CancelOutboxRedriveStatus.REDRIVING, null,
            "operator-1", "reason", NOW.minusSeconds(10), NOW.minusSeconds(9), null,
            "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}", null,
            "{\"decision\":\"REDRIVE_REQUIRED\"}", null);
    }
}
