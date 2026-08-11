package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveWorker;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveDispatcherTest {

    @Test
    void dispatchesRequestedIdsSequentiallyInRepositoryOrder() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        when(repository.findRequestedIds(3)).thenReturn(List.of(9L, 4L, 12L));
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, 3);

        dispatcher.dispatch();

        verify(repository).findRequestedIds(3);
        var order = inOrder(worker);
        order.verify(worker).start(9L);
        order.verify(worker).start(4L);
        order.verify(worker).start(12L);
    }

    @Test
    void workerExceptionDoesNotPreventLaterIds() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        when(repository.findRequestedIds(100)).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("sensitive detail"))
            .when(worker).start(2L);
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, 100);

        dispatcher.dispatch();

        var order = inOrder(worker);
        order.verify(worker).start(1L);
        order.verify(worker).start(2L);
        order.verify(worker).start(3L);
    }

    @Test
    void emptyBatchDoesNotInvokeWorker() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveWorker worker = mock(CancelOutboxRedriveWorker.class);
        when(repository.findRequestedIds(100)).thenReturn(List.of());
        var dispatcher = new CancelOutboxRedriveDispatcher(repository, worker, 100);

        dispatcher.dispatch();

        verify(worker, never()).start(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void dispatchUsesConfiguredDefaultFixedDelayProperty() throws Exception {
        Method method = CancelOutboxRedriveDispatcher.class.getMethod("dispatch");

        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${cancel.redrive.dispatch-ms:1000}");
    }
}
