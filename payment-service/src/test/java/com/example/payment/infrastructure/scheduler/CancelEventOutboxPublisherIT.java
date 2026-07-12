package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.infrastructure.persistence.AbstractRepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 아웃박스 스케줄러 IT: 실제 DB(Testcontainers) + 스텁 KafkaTemplate.
 *
 * 이유: payment-service에 Kafka 브로커 테스트 인프라(KafkaContainer / @EmbeddedKafka)가
 * 없으므로, 무거운 브로커 없이 실 DB와 스텁으로 핵심 행동(PENDING→발행→PUBLISHED)을 검증.
 */
@DisplayName("CancelEventOutboxPublisher IT: PENDING 행 발행 + PUBLISHED 전환")
class CancelEventOutboxPublisherIT extends AbstractRepositoryTest {

    @Autowired
    CancelEventOutboxRepository outboxRepository;

    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    RedissonClient redissonClient = mock(RedissonClient.class);
    RLock lock = mock(RLock.class);

    CancelEventOutboxPublisher scheduler;

    @BeforeEach
    void setUp() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 55, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        scheduler = new CancelEventOutboxPublisher(outboxRepository, kafkaTemplate, redissonClient);
        ReflectionTestUtils.setField(scheduler, "topic", "payment.cancelled");
        ReflectionTestUtils.setField(scheduler, "lockKey", "test:lock:cancel-outbox-publisher");
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
    }

    @Test
    @DisplayName("PENDING 행 → publish() → kafkaTemplate.send() 호출 + 행 PUBLISHED")
    void publishes_pending_row_and_marks_published() {
        String payload = "{\"cancelRequestId\": 4004}";
        outboxRepository.insertPending(4004L, payload);

        // MySQL JSON 컬럼이 공백을 정규화할 수 있으므로, DB에 저장된 실제 값을 읽어 verify에 사용
        String stored = outboxRepository.findPendingBatch(1).get(0).payload();

        scheduler.publish();

        // (1) kafkaTemplate.send(topic, "4004", stored) 호출됐는지 (DB 정규화 무관)
        verify(kafkaTemplate).send(
                eq("payment.cancelled"),
                eq("4004"),
                eq(stored)
        );

        // (2) 발행 후 PENDING 배치가 비어야 함 (PUBLISHED 처리 완료)
        assertThat(outboxRepository.findPendingBatch(10)).isEmpty();
    }

    @Test
    @DisplayName("락 획득 실패 시 kafkaTemplate.send() 미호출")
    void skips_when_lock_not_acquired() throws InterruptedException {
        when(lock.tryLock(0, 55, TimeUnit.SECONDS)).thenReturn(false);
        outboxRepository.insertPending(5005L, "{\"cancelRequestId\":5005}");

        scheduler.publish();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        // 행은 여전히 PENDING
        assertThat(outboxRepository.findPendingBatch(10)).hasSize(1);
    }

    @Test
    @DisplayName("개별 행 send() 실패 시 나머지 행은 계속 발행 (per-row 실패 격리)")
    void continues_on_per_row_failure() {
        outboxRepository.insertPending(6001L, "{\"cancelRequestId\":6001}");
        outboxRepository.insertPending(6002L, "{\"cancelRequestId\":6002}");

        // 6001 행은 발행 실패, 6002 행은 성공
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka error"));

        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);

        when(kafkaTemplate.send(anyString(), eq("6001"), anyString())).thenReturn(failed);
        when(kafkaTemplate.send(anyString(), eq("6002"), anyString())).thenReturn(ok);

        scheduler.publish();

        // 6002는 PUBLISHED → findPendingBatch에 나타나지 않음
        List<CancelEventOutboxRepository.PendingOutbox> remaining = outboxRepository.findPendingBatch(10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).cancelRequestId()).isEqualTo(6001L);
    }
}
