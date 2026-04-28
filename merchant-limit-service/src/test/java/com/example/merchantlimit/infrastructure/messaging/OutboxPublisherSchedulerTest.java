package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository.PendingOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherScheduler")
class OutboxPublisherSchedulerTest {

    @Mock LimitEventOutboxRepository outboxRepository;
    @Mock LimitEventKafkaProducer kafkaProducer;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    OutboxPublisherScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxPublisherScheduler(outboxRepository, kafkaProducer, redisTemplate);
        ReflectionTestUtils.setField(scheduler, "batchSize", 1000);
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:outbox:lock");
        ReflectionTestUtils.setField(scheduler, "lockTtlSeconds", 9L);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("락 획득 실패 — findPendingBatch 미호출")
    void publish_lockNotAcquired_skips() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        scheduler.publish();

        verify(outboxRepository, never()).findPendingBatch(anyInt());
    }

    @Test
    @DisplayName("pending 없음 — kafkaProducer 미호출, 락 해제")
    void publish_noPending_doesNotPublish() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of());

        scheduler.publish();

        verify(kafkaProducer, never()).publish(anyLong(), anyString());
        verify(redisTemplate).delete("test:outbox:lock");
    }

    @Test
    @DisplayName("pending 존재, 발행 성공 — markPublished 호출")
    void publish_pending_success_marksPublished() {
        PendingOutbox outbox = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(outbox));

        scheduler.publish();

        verify(kafkaProducer).publish(1L, "{\"merchantId\":1}");
        verify(outboxRepository).markPublished(10L);
        verify(redisTemplate).delete("test:outbox:lock");
    }

    @Test
    @DisplayName("pending 존재, 발행 실패 — markPublished 미호출, 다음 건 계속 처리, 락 해제")
    void publish_pending_failure_skipsMarkPublished_andReleasesLock() {
        PendingOutbox outbox1 = new PendingOutbox(10L, 1L, "{\"merchantId\":1}");
        PendingOutbox outbox2 = new PendingOutbox(11L, 2L, "{\"merchantId\":2}");
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(outboxRepository.findPendingBatch(1000)).thenReturn(List.of(outbox1, outbox2));
        doThrow(new RuntimeException("kafka error"))
            .when(kafkaProducer).publish(1L, "{\"merchantId\":1}");

        scheduler.publish();

        verify(outboxRepository, never()).markPublished(10L);    // 실패한 건 skip
        verify(kafkaProducer).publish(2L, "{\"merchantId\":2}"); // 다음 건 계속
        verify(redisTemplate).delete("test:outbox:lock");       // 락 해제
    }

    @Test
    @DisplayName("null 반환 시 락 획득 실패로 처리 — findPendingBatch 미호출")
    void publish_nullAcquired_skips() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(null);

        scheduler.publish();

        verify(outboxRepository, never()).findPendingBatch(anyInt());
    }
}
