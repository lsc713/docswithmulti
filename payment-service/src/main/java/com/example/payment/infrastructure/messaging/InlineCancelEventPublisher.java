package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "INLINE", matchIfMissing = true)
public class InlineCancelEventPublisher implements CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public InlineCancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(long cancelRequestId, String payload) {
        try {
            kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload).get(5, TimeUnit.SECONDS);
            log.debug("[kafka] INLINE 발행 완료. cancelRequestId={}", cancelRequestId);
        } catch (Exception e) {
            log.error("[kafka] INLINE 발행 실패 → TX3 롤백. cancelRequestId={}", cancelRequestId, e);
            throw new RuntimeException("[kafka] INLINE 발행 실패. cancelRequestId=" + cancelRequestId, e);
        }
    }
}
