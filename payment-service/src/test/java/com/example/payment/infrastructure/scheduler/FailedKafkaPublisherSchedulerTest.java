package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.FailedKafkaPublisherService;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailedKafkaPublisherScheduler")
class FailedKafkaPublisherSchedulerTest {

    @Mock RedissonClient redissonClient;
    @Mock FailedKafkaPublisherService service;
    @Mock RLock lock;

    FailedKafkaPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new FailedKafkaPublisherScheduler(redissonClient, service);
        ReflectionTestUtils.setField(scheduler, "lockKey", "lock:scheduler:failed-kafka-publisher");
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("락 획득 성공 시 service.publish() 호출")
    void run_acquires_lock_and_publishes() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        scheduler.run();

        verify(service).publish();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("락 획득 실패 시 service.publish() 미호출")
    void run_skips_when_lock_unavailable() throws InterruptedException {
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.run();

        verifyNoInteractions(service);
    }
}
