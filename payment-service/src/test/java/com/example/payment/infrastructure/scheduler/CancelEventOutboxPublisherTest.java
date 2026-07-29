package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.OperationAlertPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CancelEventOutboxPublisher 단위 테스트: send 실패 시 재시도/DEAD 분기.
 * 실 DB/Kafka 없이 순수 mock으로 ack 대기 루프의 분기 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelEventOutboxPublisher: poison 분기")
class CancelEventOutboxPublisherTest {

    @Mock CancelEventOutboxRepository outboxRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock RedissonClient redissonClient;
    @Mock RLock lock;
    @Mock OperationAlertPort operationAlertPort;

    CancelEventOutboxPublisher scheduler;

    @BeforeEach
    void setUp() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(0, 55, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);

        scheduler = new CancelEventOutboxPublisher(outboxRepository, kafkaTemplate, redissonClient, operationAlertPort);
        ReflectionTestUtils.setField(scheduler, "topic", "payment.cancelled");
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:lock:cancel-outbox-publisher");
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
        ReflectionTestUtils.setField(scheduler, "maxRetries", 10);
    }

    @Test
    @DisplayName("retryCount=0 건 send 실패 → bumpRetry만 호출, markDead/alert 미호출")
    void send_failure_below_max_retries_bumps_retry_only() {
        var pending = new CancelEventOutboxRepository.PendingOutbox(1L, 1001L, "{\"cancelRequestId\":1001}", 0);
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(pending));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        scheduler.publish();

        verify(outboxRepository).bumpRetry(eq(1L), anyString());
        verify(outboxRepository, never()).markDead(anyLong(), anyString());
        verify(operationAlertPort, never()).alert(anyString());
        verify(outboxRepository).markPublished(List.of());
    }

    @Test
    @DisplayName("retryCount=9(=max-1) 건 send 실패 → markDead + alert(outboxId 포함)")
    void send_failure_at_max_retries_marks_dead_and_alerts() {
        var pending = new CancelEventOutboxRepository.PendingOutbox(2L, 1002L, "{\"cancelRequestId\":1002}", 9);
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(pending));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        scheduler.publish();

        verify(outboxRepository).markDead(eq(2L), anyString());
        verify(outboxRepository, never()).bumpRetry(anyLong(), anyString());
        verify(operationAlertPort).alert(contains("outboxId=2"));
    }

    @Test
    @DisplayName("send 성공 건은 기존대로 markPublished")
    void send_success_marks_published() {
        var pending = new CancelEventOutboxRepository.PendingOutbox(3L, 1003L, "{\"cancelRequestId\":1003}", 0);
        when(outboxRepository.findPendingBatch(100)).thenReturn(List.of(pending));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        scheduler.publish();

        verify(outboxRepository).markPublished(List.of(3L));
        verify(outboxRepository, never()).bumpRetry(anyLong(), anyString());
        verify(outboxRepository, never()).markDead(anyLong(), anyString());
        verify(operationAlertPort, never()).alert(anyString());
    }

    @Test
    @DisplayName("purge: RLock 획득 후 purgePublished(retentionDays) 호출")
    void purge_acquires_lock_then_purges_published() throws InterruptedException {
        when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "purgeLockKey", "test:lock:cancel-outbox-purge");
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
        when(outboxRepository.purgePublished(7)).thenReturn(3);

        scheduler.purge();

        verify(outboxRepository).purgePublished(7);
    }

    @Test
    @DisplayName("purge: 락 획득 실패 시 purgePublished 미호출")
    void purge_skips_when_lock_not_acquired() throws InterruptedException {
        when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(false);
        ReflectionTestUtils.setField(scheduler, "purgeLockKey", "test:lock:cancel-outbox-purge");
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7);

        scheduler.purge();

        verify(outboxRepository, never()).purgePublished(anyInt());
    }
}
