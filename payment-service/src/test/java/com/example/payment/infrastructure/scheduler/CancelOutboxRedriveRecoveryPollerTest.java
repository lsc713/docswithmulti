package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveDeadlineWorker;
import com.example.payment.application.service.CancelOutboxRedriveStalePublishWorker;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveRecoveryPollerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveObservationSeconds(long observationSeconds) {
        assertThatThrownBy(() -> poller(observationSeconds, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("observationSeconds must be greater than 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int batchSize) {
        assertThatThrownBy(() -> poller(60, batchSize))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("batchSize must be greater than 0");
    }

    @Test
    void scansUnpublishedThenPublishedAtSameCutoffAndOnlySubmitsWork() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveStalePublishWorker staleWorker = mock(CancelOutboxRedriveStalePublishWorker.class);
        CancelOutboxRedriveDeadlineWorker deadlineWorker = mock(CancelOutboxRedriveDeadlineWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        Clock clock = mock(Clock.class);
        CancelOutboxRedrive unpublished = redrive(7L, null);
        CancelOutboxRedrive published = redrive(8L, "ACK");
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(1));
        when(repository.findExpiredUnpublished(NOW.minusSeconds(60), 37))
            .thenReturn(List.of(unpublished));
        when(repository.findExpiredPublished(NOW.minusSeconds(60), 37))
            .thenReturn(List.of(published));
        when(executor.tryExecute(any())).thenReturn(true);
        var poller = new CancelOutboxRedriveRecoveryPoller(
            repository, staleWorker, deadlineWorker, executor,
            clock, 60, 37);

        poller.poll();

        verify(clock).instant();
        var repositoryOrder = inOrder(repository);
        repositoryOrder.verify(repository).findExpiredUnpublished(NOW.minusSeconds(60), 37);
        repositoryOrder.verify(repository).findExpiredPublished(NOW.minusSeconds(60), 37);
        verify(staleWorker, never()).expire(any());
        verify(deadlineWorker, never()).check(any());

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, org.mockito.Mockito.times(2)).tryExecute(tasks.capture());
        tasks.getAllValues().get(0).run();
        verify(staleWorker).expire(unpublished);
        verify(deadlineWorker, never()).check(any());

        tasks.getAllValues().get(1).run();
        verify(deadlineWorker).check(published);
    }

    @Test
    void rejectedRecoverySubmissionDoesNotInvokeWorkersOrWriteRepositoryState() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveStalePublishWorker staleWorker = mock(CancelOutboxRedriveStalePublishWorker.class);
        CancelOutboxRedriveDeadlineWorker deadlineWorker = mock(CancelOutboxRedriveDeadlineWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        when(repository.findExpiredUnpublished(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(redrive(7L, null)));
        when(repository.findExpiredPublished(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(redrive(8L, "ACK")));
        when(executor.tryExecute(any())).thenReturn(false);
        var poller = new CancelOutboxRedriveRecoveryPoller(
            repository, staleWorker, deadlineWorker, executor,
            Clock.fixed(NOW, ZoneOffset.UTC), 60, 100);

        poller.poll();

        verify(staleWorker, never()).expire(any());
        verify(deadlineWorker, never()).check(any());
        verify(repository, never()).failPublish(anyLong(), any(), any(), any());
        verify(repository, never()).failConvergence(anyLong(), any(), any(), any());
    }

    @Test
    void workerExceptionIsContainedInsideSubmittedRunnable() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveStalePublishWorker staleWorker = mock(CancelOutboxRedriveStalePublishWorker.class);
        CancelOutboxRedriveDeadlineWorker deadlineWorker = mock(CancelOutboxRedriveDeadlineWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        CancelOutboxRedrive unpublished = redrive(7L, null);
        when(repository.findExpiredUnpublished(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(unpublished));
        doThrow(new IllegalStateException("sensitive detail")).when(staleWorker).expire(unpublished);
        when(executor.tryExecute(any())).thenReturn(true);
        var poller = new CancelOutboxRedriveRecoveryPoller(
            repository, staleWorker, deadlineWorker, executor,
            Clock.fixed(NOW, ZoneOffset.UTC), 60, 100);

        poller.poll();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).tryExecute(task.capture());

        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();
    }

    @Test
    void pollUsesConfiguredDefaultSchedulingProperties() throws Exception {
        Method method = CancelOutboxRedriveRecoveryPoller.class.getMethod("poll");

        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${cancel.redrive.recovery-ms:2000}");
        assertThat(method.getAnnotation(Scheduled.class).initialDelayString())
            .isEqualTo("${cancel.redrive.recovery-initial-delay-ms:2000}");
    }

    private CancelOutboxRedriveRecoveryPoller poller(long observationSeconds, int batchSize) {
        return new CancelOutboxRedriveRecoveryPoller(
            mock(CancelOutboxRedriveRepository.class),
            mock(CancelOutboxRedriveStalePublishWorker.class),
            mock(CancelOutboxRedriveDeadlineWorker.class),
            mock(CancelOutboxRedriveTaskExecutor.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            observationSeconds,
            batchSize);
    }

    private CancelOutboxRedrive redrive(long id, String result) {
        return CancelOutboxRedrive.reconstitute(
            id,
            41L + id,
            CancelOutboxRedriveStatus.REDRIVING,
            null,
            "operator-1",
            "reason",
            NOW.minusSeconds(70),
            NOW.minusSeconds(69),
            null,
            "{\"topic\":\"payment.cancelled\",\"partition\":0,\"offset\":12}",
            result,
            "{\"decision\":\"REDRIVE_REQUIRED\"}",
            null);
    }
}
