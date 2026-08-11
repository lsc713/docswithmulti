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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveConvergenceWorkerTest {

    private static final long REDRIVE_ID = 7L;
    private static final long SOURCE_OUTBOX_ID = 41L;
    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");
    private static final String FINAL_SNAPSHOT = "{\"decision\":\"ALREADY_APPLIED\","
        + "\"reasonCode\":null,\"order\":{\"status\":\"APPLIED\",\"evidence\":[]},"
        + "\"stock\":{\"status\":\"APPLIED\",\"evidence\":[]}}";

    private CancelOutboxRedriveRepository repository;
    private CancelOutboxInspectionUseCase inspection;
    private CancelOutboxRedriveConvergenceWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(CancelOutboxRedriveRepository.class);
        inspection = mock(CancelOutboxInspectionUseCase.class);
        worker = new CancelOutboxRedriveConvergenceWorker(
            repository,
            inspection,
            new CancelOutboxRedriveAuditJson(new ObjectMapper()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void alreadyAppliedWritesResolvedWithFinalSnapshotAndCompletionTime() {
        CancelOutboxRedrive redrive = redriving();
        when(inspection.inspect(SOURCE_OUTBOX_ID))
            .thenReturn(result(CancelOutboxDecision.ALREADY_APPLIED, null,
                CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED));
        when(repository.resolve(REDRIVE_ID, FINAL_SNAPSHOT, NOW)).thenReturn(true);

        worker.check(redrive);

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository).resolve(REDRIVE_ID, FINAL_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void partialConvergenceWritesNothing() {
        CancelOutboxRedrive redrive = redriving();
        when(inspection.inspect(SOURCE_OUTBOX_ID))
            .thenReturn(result(CancelOutboxDecision.REDRIVE_REQUIRED, null,
                CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.NOT_APPLIED));

        worker.check(redrive);

        verify(inspection).inspect(SOURCE_OUTBOX_ID);
        verify(repository, never()).resolve(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void unknownAndNotEligibleWriteNothing() {
        for (CancelOutboxDecision decision : List.of(
            CancelOutboxDecision.UNKNOWN,
            CancelOutboxDecision.NOT_ELIGIBLE)) {
            CancelOutboxRedriveRepository isolatedRepository = mock(CancelOutboxRedriveRepository.class);
            CancelOutboxInspectionUseCase isolatedInspection = mock(CancelOutboxInspectionUseCase.class);
            var isolatedWorker = new CancelOutboxRedriveConvergenceWorker(
                isolatedRepository,
                isolatedInspection,
                new CancelOutboxRedriveAuditJson(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC));
            when(isolatedInspection.inspect(SOURCE_OUTBOX_ID)).thenReturn(result(
                decision,
                decision == CancelOutboxDecision.UNKNOWN
                    ? CancelOutboxReasonCode.DOWNSTREAM_UNKNOWN
                    : CancelOutboxReasonCode.INCONSISTENT_DOWNSTREAM_STATE,
                CancelRestoreLegStatus.UNKNOWN,
                CancelRestoreLegStatus.UNKNOWN));

            isolatedWorker.check(redriving());

            verify(isolatedInspection).inspect(SOURCE_OUTBOX_ID);
            verifyNoMoreInteractions(isolatedRepository);
        }
    }

    @Test
    void falseResolveCasIsBenignAndIsNotRetried() {
        CancelOutboxRedrive redrive = redriving();
        when(inspection.inspect(SOURCE_OUTBOX_ID))
            .thenReturn(result(CancelOutboxDecision.ALREADY_APPLIED, null,
                CancelRestoreLegStatus.APPLIED, CancelRestoreLegStatus.APPLIED));
        when(repository.resolve(REDRIVE_ID, FINAL_SNAPSHOT, NOW)).thenReturn(false);

        worker.check(redrive);

        verify(repository).resolve(REDRIVE_ID, FINAL_SNAPSHOT, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void constructorHasNoReplayPortDependency() {
        assertThat(Arrays.stream(CancelOutboxRedriveConvergenceWorker.class.getConstructors())
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
            REDRIVE_ID,
            SOURCE_OUTBOX_ID,
            CancelOutboxRedriveStatus.REDRIVING,
            null,
            "operator-1",
            "reason",
            NOW.minusSeconds(10),
            NOW.minusSeconds(9),
            null,
            "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}",
            null,
            "{\"decision\":\"REDRIVE_REQUIRED\"}",
            null);
    }
}
