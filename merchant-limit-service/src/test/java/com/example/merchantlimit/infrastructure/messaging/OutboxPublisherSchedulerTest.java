package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository.PendingOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherScheduler (Redisson RLock)")
class OutboxPublisherSchedulerTest {

    @Mock LimitEventOutboxRepository outboxRepository;
    @Mock LimitEventKafkaProducer kafkaProducer;
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;

    OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaProducer, redissonClient);
        ReflectionTestUtils.setField(scheduler, "batchSize", 1000);
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:outbox:lock");
        when(redissonClient.getLock("test:outbox:lock")).thenReturn(lock);
    }

    @Test
    @DisplayName("락 획득 실패 — findPendingBatch·unlock 미호출(fail-safe skip)")
    void publish_lockNotAcquired_skips() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.publish();

        verify(outboxRepository, never()).findPendingBatch(anyInt());
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("락 보유 — 처리 후 소유권 확인하고 unlock")
    void publish_held_unlocksWhenOwner() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of());

        scheduler.publish();

        verify(lock).unlock();
    }

    @Test
    @DisplayName("finally에서 소유권 없으면(리스 만료 후 타 인스턴스 점유) unlock 미호출 — 남의 락 삭제 방지")
    void publish_notOwnerAtFinally_doesNotUnlock() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of());

        scheduler.publish();

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("pending 발행 성공 — markPublished 호출")
    void publish_pending_success_marksPublished() throws InterruptedException {
        PendingOutbox outbox = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(outbox));

        scheduler.publish();

        verify(kafkaProducer).publish(1L, "{\"merchantId\":1}");
        verify(outboxRepository).markPublished(10L);
    }

    @Test
    @DisplayName("발행 실패 — 실패건 markPublished 미호출·다음건 계속·unlock")
    void publish_failure_skipsMark_continuesNext_unlocks() throws InterruptedException {
        PendingOutbox o1 = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        PendingOutbox o2 = new PendingOutbox(11L, 2L, "{\"merchantId\":2}");
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(o1, o2));
        doThrow(new RuntimeException("kafka error")).when(kafkaProducer).publish(1L, "{\"merchantId\":1}");

        scheduler.publish();

        verify(outboxRepository, never()).markPublished(10L);
        verify(kafkaProducer).publish(2L, "{\"merchantId\":2}");
        verify(lock).unlock();
    }
}
