package com.example.payment.application.service;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import com.example.payment.application.interfaces.FailedKafkaEventRepository.PendingFailedEvent;
import com.example.payment.application.interfaces.OperationAlertPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FailedKafkaPublisherService")
class FailedKafkaPublisherServiceTest {

    @Mock FailedKafkaEventRepository repo;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock OperationAlertPort alertPort;

    FailedKafkaPublisherService service;

    @BeforeEach
    void setUp() {
        service = new FailedKafkaPublisherService(repo, kafkaTemplate, alertPort);
    }

    private PendingFailedEvent pendingEvent(long id, int retryCount) {
        return new PendingFailedEvent(id, "payment.cancelled", "{\"cancelRequestId\":" + id + "}", retryCount);
    }

    @Test
    @DisplayName("PENDING 건 Kafka 재발행 성공 → markPublished 호출")
    void retry_success_marks_published() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 1)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        service.publish();

        verify(repo).markPublished(1L);
        verify(repo, never()).incrementRetry(anyLong(), anyString());
    }

    @Test
    @DisplayName("Kafka 재발행 실패, retryCount < 5 → incrementRetry 호출")
    void retry_failure_increments_retry() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 3)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        service.publish();

        verify(repo).incrementRetry(eq(1L), anyString());
        verify(repo, never()).markExhausted(anyLong(), anyString());
    }

    @Test
    @DisplayName("retryCount 4 (5번째 실패) → EXHAUSTED + 운영 알림")
    void retry_failure_at_max_marks_exhausted_and_alerts() throws Exception {
        when(repo.findPendingBatch(100)).thenReturn(List.of(pendingEvent(1L, 4)));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        service.publish();

        verify(repo).markExhausted(eq(1L), anyString());
        verify(alertPort).alert(anyString());
        verify(repo, never()).incrementRetry(anyLong(), anyString());
    }

    @Test
    @DisplayName("PENDING 없으면 아무 동작 없음")
    void no_pending_no_op() {
        when(repo.findPendingBatch(100)).thenReturn(List.of());

        service.publish();

        verifyNoMoreInteractions(kafkaTemplate, alertPort);
    }
}
