package com.example.payment.infrastructure.messaging;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelEventPublisher")
class CancelEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock FailedKafkaEventRepository failedKafkaEventRepository;

    CancelEventPublisher publisher;

    private static final String TOPIC = "payment.cancelled";

    @BeforeEach
    void setUp() {
        publisher = new CancelEventPublisher(kafkaTemplate, failedKafkaEventRepository, TOPIC);
    }

    private CancelCompletedEvent event() {
        return new CancelCompletedEvent(
            1L, "pay_abc", 100L, Instant.parse("2026-04-28T10:00:00Z"),
            List.of(new CancelCompletedEvent.CancelledItemData(10L, 20L, BigDecimal.valueOf(30000)))
        );
    }

    @Test
    @DisplayName("Kafka 발행 성공 시 failed_kafka_event 저장 없음")
    void publish_success_no_failed_event() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.onCancelCompleted(event());

        verify(kafkaTemplate).send(eq(TOPIC), eq("1"), anyString());
        verifyNoInteractions(failedKafkaEventRepository);
    }

    @Test
    @DisplayName("Kafka 발행 실패 시 failed_kafka_event INSERT")
    void publish_failure_saves_failed_event() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));
        when(failedKafkaEventRepository.existsByCancelRequestId(1L)).thenReturn(false);

        publisher.onCancelCompleted(event());

        verify(failedKafkaEventRepository).saveIfAbsent(eq(1L), eq(TOPIC), anyString());
    }

    @Test
    @DisplayName("Kafka 발행 실패 + 이미 failed_event 존재 시 중복 저장 없음")
    void publish_failure_already_exists_no_duplicate() throws Exception {
        when(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka down")));
        when(failedKafkaEventRepository.existsByCancelRequestId(1L)).thenReturn(true);

        publisher.onCancelCompleted(event());

        verify(failedKafkaEventRepository, never()).saveIfAbsent(anyLong(), anyString(), anyString());
    }
}
