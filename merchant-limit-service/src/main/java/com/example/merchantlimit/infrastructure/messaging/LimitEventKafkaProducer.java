package com.example.merchantlimit.infrastructure.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LimitEventKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kafka.topic.merchant-limit-updated}")
    private String topic;

    /**
     * merchantId를 파티션 키로 사용 — 같은 가맹점 이벤트 순서 보장
     * .get()으로 블로킹하여 Kafka 발행 실패 시 예외를 호출자에게 전파한다.
     * OutboxPublisherScheduler의 per-item try/catch가 markPublished 호출을 건너뛰게 된다.
     */
    public void publish(long merchantId, String payload) {
        try {
            kafkaTemplate.send(topic, String.valueOf(merchantId), payload).get();
            log.debug("Kafka 발행 완료. merchantId={}", merchantId);
        } catch (Exception e) {
            log.error("Kafka 발행 실패. merchantId={}, payload={}", merchantId, payload, e);
            throw new RuntimeException("Kafka 발행 실패. merchantId=" + merchantId, e);
        }
    }
}
