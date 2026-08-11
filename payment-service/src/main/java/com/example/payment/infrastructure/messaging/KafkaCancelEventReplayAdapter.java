package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventReplayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class KafkaCancelEventReplayAdapter implements CancelEventReplayPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final long publishTimeoutMs;

    public KafkaCancelEventReplayAdapter(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic,
        @Value("${cancel.redrive.publish-timeout-ms:5000}") long publishTimeoutMs
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    @Override
    public ReplayResult replay(long cancelRequestId, String payload) {
        try {
            var sendResult = kafkaTemplate
                .send(topic, String.valueOf(cancelRequestId), payload)
                .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            var metadata = sendResult.getRecordMetadata();
            return new ReplayResult(metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelEventReplayException(cancelRequestId, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new CancelEventReplayException(cancelRequestId, e);
        }
    }
}

final class CancelEventReplayException extends RuntimeException {

    CancelEventReplayException(long cancelRequestId, Throwable cause) {
        super("Cancel event replay failed. cancelRequestId=" + cancelRequestId, cause);
    }
}
