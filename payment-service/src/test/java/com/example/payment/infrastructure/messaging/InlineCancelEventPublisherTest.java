package com.example.payment.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InlineCancelEventPublisherTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    InlineCancelEventPublisher publisher;

    @BeforeEach void setUp() {
        publisher = new InlineCancelEventPublisher(kafkaTemplate, "payment.cancelled");
    }

    @Test
    @DisplayName("발행 성공 시 send 호출, 예외 없음")
    void publishes_ok() {
        CompletableFuture<SendResult<String,String>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(eq("payment.cancelled"), eq("77"), anyString())).willReturn(ok);
        publisher.publish(77L, "{\"cancelRequestId\":77}");
        verify(kafkaTemplate).send("payment.cancelled", "77", "{\"cancelRequestId\":77}");
    }

    @Test
    @DisplayName("발행 실패 시 RuntimeException (TX3 롤백 유도)")
    void publish_failure_throws() {
        CompletableFuture<SendResult<String,String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failed);
        assertThatThrownBy(() -> publisher.publish(77L, "{}"))
            .isInstanceOf(RuntimeException.class);
    }
}
