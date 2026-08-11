package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveConvergenceWorker;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveConvergencePollerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int batchSize) {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);

        assertThatThrownBy(() -> new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            batchSize))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("batchSize must be greater than 0");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveObservationSeconds(long observationSeconds) {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);

        assertThatThrownBy(() -> new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            observationSeconds,
            100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("observationSeconds must be greater than 0");
    }

    @Test
    void queriesRecentWindowAndSubmitsWithoutRunningWorkerOnCallingThread() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        CancelOutboxRedrive first = redrive(9L);
        CancelOutboxRedrive second = redrive(4L);
        when(repository.findConverging(NOW.minusSeconds(17), 37))
            .thenReturn(List.of(first, second));
        when(executor.tryExecute(any())).thenReturn(true);
        var poller = new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            17,
            37);

        poller.poll();

        verify(repository).findConverging(NOW.minusSeconds(17), 37);
        verify(worker, never()).check(any());
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, org.mockito.Mockito.times(2)).tryExecute(tasks.capture());

        tasks.getAllValues().forEach(Runnable::run);

        verify(worker).check(first);
        verify(worker).check(second);
    }

    @Test
    void workerExceptionIsContainedInsideSubmittedRunnable() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        CancelOutboxRedrive second = redrive(2L);
        when(repository.findConverging(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(second));
        when(executor.tryExecute(any())).thenReturn(true);
        doThrow(new IllegalStateException("sensitive detail"))
            .when(worker).check(second);
        var poller = new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            100);

        poller.poll();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).tryExecute(task.capture());

        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();
    }

    @Test
    void rejectedSubmissionDoesNotInvokeWorkerOrWriteRepositoryState() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        when(repository.findConverging(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(redrive(1L)));
        when(executor.tryExecute(any())).thenReturn(false);
        var poller = new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            executor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            100);

        poller.poll();

        verify(worker, never()).check(any());
        verify(repository, never()).resolve(org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(repository, never()).failConvergence(
            org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    @Test
    void pollUsesConfiguredDefaultSchedulingPropertiesAndHasNoReplayDependency() throws Exception {
        Method method = CancelOutboxRedriveConvergencePoller.class.getMethod("poll");

        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${cancel.redrive.convergence-ms:2000}");
        assertThat(method.getAnnotation(Scheduled.class).initialDelayString())
            .isEqualTo("${cancel.redrive.convergence-initial-delay-ms:2000}");
        assertThat(Arrays.stream(CancelOutboxRedriveConvergencePoller.class.getConstructors())
            .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
            .doesNotContain(CancelEventReplayPort.class);
    }

    private CancelOutboxRedrive redrive(long id) {
        return CancelOutboxRedrive.reconstitute(
            id,
            41L + id,
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
