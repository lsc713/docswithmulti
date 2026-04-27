package com.example.payment.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox payload를 Kafka에 발행.
 * KafkaTemplate.send()는 CompletableFuture 반환 — get()으로 블로킹하여 실패를 즉시 감지.
 */
@Slf4j
@Component
public class KafkaOutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOutboxPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${kafka.topic.payment-cancelled}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * @param cancelRequestId 파티션 키 (같은 결제건 순서 보장)
     * @param payload         Outbox JSON payload 그대로 발행
     * @throws Exception      Kafka 발행 실패 시 — 호출자가 처리
     */
    public void publish(long cancelRequestId, String payload) throws Exception {
        kafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload)
            .get(5, TimeUnit.SECONDS);
        log.debug("[kafka] 발행 완료. topic={}, cancelRequestId={}", topic, cancelRequestId);
    }
}
