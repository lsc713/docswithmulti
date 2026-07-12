package com.example.payment.infrastructure.messaging;

import com.example.payment.application.interfaces.CancelEventPublisher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** INLINE_ASYNC: fire-and-forget. dual-write 구멍 존재 — 측정 전용, 프로덕션 금지. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "INLINE_ASYNC")
public class InlineAsyncCancelEventPublisher implements CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public InlineAsyncCancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @PostConstruct
    void warn() {
        log.warn("[publish] cancel.publish.mode=INLINE_ASYNC — 측정 전용(dual-write 안전하지 않음). 프로덕션 사용 금지.");
    }

    @Override
    public void publish(long cancelRequestId, String payload) {
        kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload); // .get() 없음 — 실패 감지 안 함
    }
}
