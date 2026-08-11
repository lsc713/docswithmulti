package com.example.payment.infrastructure.scheduler;

import com.example.payment.infrastructure.config.CancelOutboxRedriveExecutorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class CancelOutboxRedriveTaskExecutorTest {

    private ThreadPoolTaskExecutor configuredExecutor;

    @AfterEach
    void shutDownExecutor() {
        if (configuredExecutor != null) {
            configuredExecutor.shutdown();
        }
    }

    @Test
    void runsFiveTasksConcurrentlyRejectsWithoutQueueAndAcceptsAfterSlotReturns() throws Exception {
        CancelOutboxRedriveTaskExecutor executor = newExecutor();
        CountDownLatch allStarted = new CountDownLatch(5);
        List<CountDownLatch> releases = new ArrayList<>();
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();

        for (int index = 0; index < 5; index++) {
            CountDownLatch release = new CountDownLatch(1);
            releases.add(release);
            assertThat(executor.tryExecute(() -> {
                allStarted.countDown();
                await(release);
            })).isTrue();
        }

        assertThat(allStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.activeCount()).isEqualTo(5);
        assertThat(executor.tryExecute(() -> rejectedTaskRan.set(true))).isFalse();
        assertThat(rejectedTaskRan).isFalse();

        releases.getFirst().countDown();
        assertThat(waitForActiveCount(executor, 4, Duration.ofSeconds(2))).isTrue();
        CountDownLatch laterTaskRan = new CountDownLatch(1);
        assertThat(executor.tryExecute(laterTaskRan::countDown)).isTrue();
        assertThat(laterTaskRan.await(2, TimeUnit.SECONDS)).isTrue();

        releases.forEach(CountDownLatch::countDown);
    }

    @Test
    void usesCancelRedriveThreadNamesDuringExecution() throws Exception {
        CancelOutboxRedriveTaskExecutor executor = newExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        assertThat(executor.tryExecute(() -> {
            threadName.set(Thread.currentThread().getName());
            ran.countDown();
        })).isTrue();

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("cancel-redrive-");
    }

    @Test
    void shutdownWaitsForRunningTaskToComplete() throws Exception {
        CancelOutboxRedriveTaskExecutor executor = newExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        assertThat(executor.tryExecute(() -> {
            taskStarted.countDown();
            await(releaseTask);
        })).isTrue();
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread shutdownThread = Thread.ofPlatform().start(() -> {
            configuredExecutor.shutdown();
            shutdownReturned.countDown();
        });

        assertThat(shutdownReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
        releaseTask.countDown();
        assertThat(shutdownReturned.await(2, TimeUnit.SECONDS)).isTrue();
        shutdownThread.join();
        configuredExecutor = null;
    }

    @Test
    void propagatesFailuresOtherThanSpringTaskRejection() {
        ThreadPoolTaskExecutor springExecutor = mock(ThreadPoolTaskExecutor.class);
        doThrow(new IllegalStateException("executor not initialized"))
            .when(springExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        CancelOutboxRedriveTaskExecutor executor = new CancelOutboxRedriveTaskExecutor(springExecutor);

        assertThatThrownBy(() -> executor.tryExecute(() -> { }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("executor not initialized");
    }

    private CancelOutboxRedriveTaskExecutor newExecutor() {
        configuredExecutor = new CancelOutboxRedriveExecutorConfig().cancelRedriveExecutor(5, 10);
        configuredExecutor.initialize();
        return new CancelOutboxRedriveTaskExecutor(configuredExecutor);
    }

    private boolean waitForActiveCount(
        CancelOutboxRedriveTaskExecutor executor,
        int expected,
        Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (executor.activeCount() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return executor.activeCount() == expected;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
