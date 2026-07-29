package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxCancelEventPublisherTest {

    private static final String WAKE_TOPIC = "cancel-outbox-wake";

    @Mock CancelEventOutboxRepository outboxRepository;
    @Mock RedissonClient redissonClient;
    @Mock RTopic rTopic;
    @InjectMocks OutboxCancelEventPublisher publisher;

    @AfterEach
    void cleanupSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("publish는 outbox INSERT를 위임한다")
    void inserts_outbox() {
        publisher.publish(88L, "{\"cancelRequestId\":88}");
        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
    }

    @Test
    @DisplayName("트랜잭션 활성 시: insertPending은 즉시, wake는 커밋 후에만 발사된다")
    void wakes_only_after_commit_when_transaction_active() {
        when(redissonClient.getTopic(WAKE_TOPIC)).thenReturn(rTopic);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(88L, "{\"cancelRequestId\":88}");

        // insertPending은 커밋 전에 즉시 호출되어야 한다 (TX3 원자성 유지)
        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
        // 커밋 전에는 wake가 발사되면 안 된다
        verify(rTopic, never()).publish(any());

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(rTopic).publish(88L);
    }

    @Test
    @DisplayName("트랜잭션 없을 때: wake는 즉시 발사된다(폴백)")
    void wakes_immediately_when_no_transaction() {
        when(redissonClient.getTopic(WAKE_TOPIC)).thenReturn(rTopic);

        publisher.publish(88L, "{\"cancelRequestId\":88}");

        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
        verify(rTopic).publish(88L);
    }

    @Test
    @DisplayName("wake 발사 실패는 삼켜지고 publish()는 예외를 던지지 않는다(poll backstop이 커버)")
    void swallow_wake_failure() {
        when(redissonClient.getTopic(WAKE_TOPIC)).thenReturn(rTopic);
        doThrow(new RuntimeException("redis down")).when(rTopic).publish(any());

        publisher.publish(88L, "{\"cancelRequestId\":88}");

        verify(outboxRepository).insertPending(88L, "{\"cancelRequestId\":88}");
    }
}
