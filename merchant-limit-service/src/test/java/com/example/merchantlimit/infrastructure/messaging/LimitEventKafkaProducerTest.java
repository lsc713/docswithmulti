package com.example.merchantlimit.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LimitEventKafkaProducer")
class LimitEventKafkaProducerTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;

    LimitEventKafkaProducer producer;

    @BeforeEach
    void setUp() {
        producer = new LimitEventKafkaProducer(kafkaTemplate);
        ReflectionTestUtils.setField(producer, "topic", "merchant.limit.updated");
    }

    @Test
    @DisplayName("발행 성공 — merchantId 파티션 키로 Kafka 전송")
    void publish_success() throws Exception {
        when(kafkaTemplate.send(eq("merchant.limit.updated"), eq("1"), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        producer.publish(1L, "{\"merchantId\":1}");

        verify(kafkaTemplate).send("merchant.limit.updated", "1", "{\"merchantId\":1}");
    }

    @Test
    @DisplayName("발행 실패 — RuntimeException 전파")
    void publish_failure_propagates_exception() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(
                new ExecutionException("kafka error", new RuntimeException())));

        assertThatThrownBy(() -> producer.publish(1L, "{\"merchantId\":1}"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Kafka 발행 실패");
    }
}
