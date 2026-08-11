package com.example.payment.application.service;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.usecase.CancelOutboxInspectionUseCase;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveStalePublishWorkerTest {

    private static final long REDRIVE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");
    private static final String BEFORE_STATE = "{\"decision\":\"ALREADY_APPLIED\"}";

    private CancelOutboxRedriveRepository repository;
    private CancelOutboxRedriveStalePublishWorker worker;

    @BeforeEach
    void setUp() {
        repository = mock(CancelOutboxRedriveRepository.class);
        worker = new CancelOutboxRedriveStalePublishWorker(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void expiryOnlyMarksPublishStateUnknownWithRecordedBeforeState() {
        when(repository.failPublish(REDRIVE_ID, "PUBLISH_STATE_UNKNOWN", BEFORE_STATE, NOW)).thenReturn(true);

        worker.expire(unpublished());

        verify(repository).failPublish(REDRIVE_ID, "PUBLISH_STATE_UNKNOWN", BEFORE_STATE, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void falseTerminalCasIsBenign() {
        when(repository.failPublish(REDRIVE_ID, "PUBLISH_STATE_UNKNOWN", BEFORE_STATE, NOW)).thenReturn(false);

        worker.expire(unpublished());

        verify(repository).failPublish(REDRIVE_ID, "PUBLISH_STATE_UNKNOWN", BEFORE_STATE, NOW);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void constructorHasNoInspectionOrReplayDependency() {
        assertThat(Arrays.stream(CancelOutboxRedriveStalePublishWorker.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
            .doesNotContain(CancelOutboxInspectionUseCase.class, CancelEventReplayPort.class);
    }

    private CancelOutboxRedrive unpublished() {
        return CancelOutboxRedrive.reconstitute(
            REDRIVE_ID, 41L, CancelOutboxRedriveStatus.REDRIVING, null,
            "operator-1", "reason", NOW.minusSeconds(10), NOW.minusSeconds(9), null,
            null, null, BEFORE_STATE, null);
    }
}
