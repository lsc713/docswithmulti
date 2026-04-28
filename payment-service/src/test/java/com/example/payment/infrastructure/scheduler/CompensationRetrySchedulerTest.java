package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.CompensationRetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensationRetryScheduler")
class CompensationRetrySchedulerTest {

    @Mock RedissonClient redissonClient;
    @Mock CompensationRetryService compensationRetryService;
    @Mock RLock lock;

    CompensationRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationRetryScheduler(redissonClient, compensationRetryService);
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:lock:compensation-retry");
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("락 획득 성공 → retryAll() 호출 + 락 해제")
    void run_lockAcquired_callsRetryAllAndUnlocks() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        scheduler.run();

        verify(compensationRetryService).retryAll();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 → retryAll() 미호출")
    void run_lockNotAcquired_skipsRetryAll() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.run();

        verify(compensationRetryService, never()).retryAll();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("InterruptedException → Thread.interrupt() 설정 + retryAll() 미호출")
    void run_interrupted_setsInterruptFlagAndSkips() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test"));

        scheduler.run();

        assertTrue(Thread.currentThread().isInterrupted());
        verify(compensationRetryService, never()).retryAll();
        Thread.interrupted(); // 다음 테스트를 위해 인터럽트 플래그 초기화
    }

    @Test
    @DisplayName("retryAll() 예외 발생 시에도 finally에서 락 해제")
    void run_serviceThrows_lockStillReleased() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new RuntimeException("retry error")).when(compensationRetryService).retryAll();

        assertThrows(RuntimeException.class, () -> scheduler.run());

        verify(lock).unlock();
    }

    @Test
    @DisplayName("finally: 현재 스레드가 락을 보유하지 않으면 unlock 미호출")
    void run_lockNotHeldByCurrentThread_doesNotUnlock() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        scheduler.run();

        verify(compensationRetryService).retryAll();
        verify(lock, never()).unlock();
    }
}
