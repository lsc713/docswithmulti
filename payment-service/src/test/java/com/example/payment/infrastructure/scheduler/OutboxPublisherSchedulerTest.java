package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.OutboxPublisherService;
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
@DisplayName("OutboxPublisherScheduler")
class OutboxPublisherSchedulerTest {

    @Mock RedissonClient redissonClient;
    @Mock OutboxPublisherService outboxPublisherService;
    @Mock RLock lock;

    OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(redissonClient, outboxPublisherService);
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:lock:outbox-publisher");
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("락 획득 성공 → publish() 호출 + 락 해제")
    void run_lockAcquired_callsPublishAndUnlocks() throws InterruptedException {
        when(lock.tryLock(0, 9, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        scheduler.run();

        verify(outboxPublisherService).publish();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 → publish() 미호출")
    void run_lockNotAcquired_skipsPublish() throws InterruptedException {
        when(lock.tryLock(0, 9, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.run();

        verify(outboxPublisherService, never()).publish();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("InterruptedException → Thread.interrupt() 설정 + publish() 미호출")
    void run_interrupted_setsInterruptFlagAndSkips() throws InterruptedException {
        when(lock.tryLock(0, 9, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test"));

        scheduler.run();

        assertTrue(Thread.currentThread().isInterrupted());
        verify(outboxPublisherService, never()).publish();
        Thread.interrupted(); // 다음 테스트를 위해 인터럽트 플래그 초기화
    }

    @Test
    @DisplayName("publish() 예외 발생 시에도 finally에서 락 해제")
    void run_serviceThrows_lockStillReleased() throws InterruptedException {
        when(lock.tryLock(0, 9, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new RuntimeException("publish error")).when(outboxPublisherService).publish();

        assertThrows(RuntimeException.class, () -> scheduler.run());

        verify(lock).unlock();
    }

    @Test
    @DisplayName("finally: 현재 스레드가 락을 보유하지 않으면 unlock 미호출")
    void run_lockNotHeldByCurrentThread_doesNotUnlock() throws InterruptedException {
        when(lock.tryLock(0, 9, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        scheduler.run();

        verify(outboxPublisherService).publish();
        verify(lock, never()).unlock();
    }
}
