package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class KafkaCancelEventReplayAdapterTest {

    private static final String TOPIC = "payment.cancelled";

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    private KafkaCancelEventReplayAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new KafkaCancelEventReplayAdapter(kafkaTemplate, TOPIC, 10L);
    }

    @Test
    void zeroPublishTimeoutFailsFastBeforeSend() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new KafkaCancelEventReplayAdapter(kafkaTemplate, TOPIC, 0L))
            .withMessage("publishTimeoutMs must be greater than 0");
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void negativePublishTimeoutFailsFastBeforeSend() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new KafkaCancelEventReplayAdapter(kafkaTemplate, TOPIC, -1L))
            .withMessage("publishTimeoutMs must be greater than 0");
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void sendsExactRawKeyAndPayloadOnceAndReturnsBrokerMetadata() {
        String payload = new String("{\"cancelRequestId\":77,\"paymentKey\":\"pay-secret\"}");
        var producerRecord = new ProducerRecord<String, String>(TOPIC, "77", payload);
        var metadata = new RecordMetadata(
            new TopicPartition(TOPIC, 3), 902L, 0, 1234L, 2, payload.length());
        var sendResult = new SendResult<>(producerRecord, metadata);
        given(kafkaTemplate.send(TOPIC, "77", payload))
            .willReturn(CompletableFuture.completedFuture(sendResult));

        CancelEventReplayPort.ReplayResult result = adapter.replay(77L, payload);

        assertThat(result).isEqualTo(
            new CancelEventReplayPort.ReplayResult(TOPIC, 3, 902L));
        verify(kafkaTemplate).send(eq(TOPIC), eq("77"), same(payload));
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void timeoutThrowsDedicatedSafeReplayException() {
        String payload = "secret-payload";
        given(kafkaTemplate.send(TOPIC, "77", payload))
            .willReturn(new CompletableFuture<>());
        adapter = new KafkaCancelEventReplayAdapter(kafkaTemplate, TOPIC, 1L);

        assertThatThrownBy(() -> adapter.replay(77L, payload))
            .isInstanceOf(CancelEventReplayException.class)
            .hasMessage("Cancel event replay failed. cancelRequestId=77")
            .hasMessageNotContaining(payload)
            .hasMessageNotContaining(TOPIC);
    }

    @Test
    void exceptionalCompletionThrowsDedicatedSafeReplayException() {
        String payload = "secret-payload";
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker leaked detail"));
        given(kafkaTemplate.send(TOPIC, "77", payload)).willReturn(failed);

        assertThatThrownBy(() -> adapter.replay(77L, payload))
            .isInstanceOf(CancelEventReplayException.class)
            .hasMessage("Cancel event replay failed. cancelRequestId=77")
            .hasMessageNotContaining("broker leaked detail")
            .hasCauseInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void interruptionRestoresInterruptStatusAndThrowsDedicatedSafeReplayException() {
        String payload = "secret-payload";
        given(kafkaTemplate.send(TOPIC, "77", payload))
            .willReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> adapter.replay(77L, payload))
                .isInstanceOf(CancelEventReplayException.class)
                .hasMessage("Cancel event replay failed. cancelRequestId=77")
                .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
