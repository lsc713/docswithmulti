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
     */
    public void publish(long merchantId, String payload) {
        kafkaTemplate.send(topic, String.valueOf(merchantId), payload)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka 발행 실패. merchantId={}, payload={}", merchantId, payload, ex);
                    throw new RuntimeException("Kafka 발행 실패", ex);
                }
                log.debug("Kafka 발행 완료. merchantId={}, offset={}",
                    merchantId, result.getRecordMetadata().offset());
            });
    }
}
