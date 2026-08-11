package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
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

class CancelOutboxRedriveDispatcherTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int batchSize) {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);

        assertThatThrownBy(() -> new CancelOutboxRedriveDispatcher(repository, worker, executor, batchSize))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("batchSize must be greater than 0");
    }

    @Test
    void scansAndSubmitsRequestedIdsWithoutRunningWorkerOnCallingThread() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        when(repository.findRequestedIds(3)).thenReturn(List.of(9L, 4L, 12L));
        when(executor.tryExecute(any())).thenReturn(true);
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, executor, 3);

        dispatcher.dispatch();

        verify(repository).findRequestedIds(3);
        verify(worker, never()).start(org.mockito.ArgumentMatchers.anyLong());
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, org.mockito.Mockito.times(3)).tryExecute(tasks.capture());

        tasks.getAllValues().forEach(Runnable::run);

        verify(worker).start(9L);
        verify(worker).start(4L);
        verify(worker).start(12L);
    }

    @Test
    void workerExceptionIsContainedInsideSubmittedRunnable() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        when(repository.findRequestedIds(100)).thenReturn(List.of(2L));
        when(executor.tryExecute(any())).thenReturn(true);
        doThrow(new IllegalStateException("sensitive detail")).when(worker).start(2L);
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, executor, 100);

        dispatcher.dispatch();
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).tryExecute(task.capture());

        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();
    }

    @Test
    void rejectedSubmissionDoesNotInvokeWorkerOrWriteRepositoryState() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        CancelOutboxRedriveTaskExecutor executor = mock(CancelOutboxRedriveTaskExecutor.class);
        when(repository.findRequestedIds(100)).thenReturn(List.of(3L));
        when(executor.tryExecute(any())).thenReturn(false);
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, executor, 100);

        dispatcher.dispatch();

        verify(worker, never()).start(org.mockito.ArgumentMatchers.anyLong());
        verify(repository, never()).tryStart(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void dispatchUsesConfiguredDefaultSchedulingProperties() throws Exception {
        Method method = CancelOutboxRedriveDispatcher.class.getMethod("dispatch");

        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${cancel.redrive.dispatch-ms:1000}");
        assertThat(method.getAnnotation(Scheduled.class).initialDelayString())
            .isEqualTo("${cancel.redrive.dispatch-initial-delay-ms:1000}");
    }
}
