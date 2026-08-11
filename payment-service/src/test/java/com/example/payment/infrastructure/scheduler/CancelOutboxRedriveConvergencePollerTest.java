package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import com.example.payment.application.interfaces.CancelOutboxRedriveRepository;
import com.example.payment.application.service.CancelOutboxRedriveConvergenceWorker;
import com.example.payment.domain.entity.CancelOutboxRedrive;
import com.example.payment.domain.entity.CancelOutboxRedriveStatus;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelOutboxRedriveConvergencePollerTest {

    private static final Instant NOW = Instant.parse("2026-08-11T01:02:03Z");

    @Test
    void queriesObservationWindowAndConfiguredBatchThenChecksSequentially() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedrive first = redrive(9L);
        CancelOutboxRedrive second = redrive(4L);
        when(repository.findConverging(NOW.minusSeconds(17), 37))
            .thenReturn(List.of(first, second));
        var poller = new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            Clock.fixed(NOW, ZoneOffset.UTC),
            17,
            37);

        poller.poll();

        verify(repository).findConverging(NOW.minusSeconds(17), 37);
        var order = inOrder(worker);
        order.verify(worker).check(first);
        order.verify(worker).check(second);
    }

    @Test
    void workerExceptionDoesNotPreventLaterRows() {
        CancelOutboxRedriveRepository repository = mock(CancelOutboxRedriveRepository.class);
        CancelOutboxRedriveConvergenceWorker worker = mock(CancelOutboxRedriveConvergenceWorker.class);
        CancelOutboxRedrive first = redrive(1L);
        CancelOutboxRedrive second = redrive(2L);
        CancelOutboxRedrive third = redrive(3L);
        when(repository.findConverging(NOW.minusSeconds(60), 100))
            .thenReturn(List.of(first, second, third));
        doThrow(new IllegalStateException("sensitive detail"))
            .when(worker).check(second);
        var poller = new CancelOutboxRedriveConvergencePoller(
            repository,
            worker,
            Clock.fixed(NOW, ZoneOffset.UTC),
            60,
            100);

        poller.poll();

        var order = inOrder(worker);
        order.verify(worker).check(first);
        order.verify(worker).check(second);
        order.verify(worker).check(third);
    }

    @Test
    void pollUsesConfiguredDefaultFixedDelayPropertyAndHasNoReplayDependency() throws Exception {
        Method method = CancelOutboxRedriveConvergencePoller.class.getMethod("poll");

        assertThat(method.getAnnotation(Scheduled.class).fixedDelayString())
            .isEqualTo("${cancel.redrive.convergence-ms:2000}");
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
