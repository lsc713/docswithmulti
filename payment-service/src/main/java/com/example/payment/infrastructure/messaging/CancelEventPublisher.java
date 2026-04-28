package com.example.payment.infrastructure.messaging;

import com.example.payment.application.event.CancelCompletedEvent;
import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CancelEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final FailedKafkaEventRepository failedKafkaEventRepository;
    private final String topic;

    public CancelEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        FailedKafkaEventRepository failedKafkaEventRepository,
        @Value("${kafka.topic.payment-cancelled}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.failedKafkaEventRepository = failedKafkaEventRepository;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelCompleted(CancelCompletedEvent event) {
        String payload = buildPayload(event);
        try {
            kafkaTemplate.send(topic, String.valueOf(event.cancelRequestId()), payload)
                .get(5, TimeUnit.SECONDS);
            log.debug("[kafka] 발행 완료. cancelRequestId={}", event.cancelRequestId());
        } catch (Exception e) {
            log.error("[kafka] 발행 실패 → failed_kafka_event INSERT. cancelRequestId={}",
                event.cancelRequestId(), e);
            if (!failedKafkaEventRepository.existsByCancelRequestId(event.cancelRequestId())) {
                failedKafkaEventRepository.saveIfAbsent(event.cancelRequestId(), topic, payload);
            }
        }
    }

    private String buildPayload(CancelCompletedEvent event) {
        String itemsJson = event.cancelledItems().stream()
            .map(i -> String.format(
                "{\"paymentItemId\":%d,\"orderItemId\":%d,\"itemAmount\":%s}",
                i.paymentItemId(), i.orderItemId(), i.itemAmount().toPlainString()
            ))
            .collect(Collectors.joining(",", "[", "]"));

        return String.format(
            "{\"cancelRequestId\":%d,\"paymentKey\":\"%s\",\"merchantId\":%d," +
            "\"cancelledItems\":%s,\"cancelledAt\":\"%s\"}",
            event.cancelRequestId(),
            event.paymentKey(),
            event.merchantId(),
            itemsJson,
            event.cancelledAt()
        );
    }
}
