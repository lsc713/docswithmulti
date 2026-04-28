package com.example.payment.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherTest {

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    KafkaOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaOutboxPublisher(kafkaTemplate, "payment.cancelled");
    }

    @Test
    void publish_success() throws Exception {
        when(kafkaTemplate.send("payment.cancelled", "1", "{}"))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(1L, "{}");

        verify(kafkaTemplate, times(1)).send("payment.cancelled", "1", "{}");
    }

    @Test
    void publish_failure_propagates_exception() {
        CompletableFuture<Object> failedFuture =
                CompletableFuture.failedFuture(new ExecutionException("kafka error", new RuntimeException()));

        when(kafkaTemplate.send("payment.cancelled", "1", "{}"))
                .thenReturn((CompletableFuture) failedFuture);

        assertThatThrownBy(() -> publisher.publish(1L, "{}"))
                .isInstanceOf(Exception.class);
    }
}
