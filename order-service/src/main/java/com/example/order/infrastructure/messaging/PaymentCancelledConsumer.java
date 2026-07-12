package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledConsumer {

    private final ProcessCancelledItemsUseCase processUseCase;
    private final RetryRouter retryRouter;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);
            List<Long> orderItemIds = payload.cancelledItems().stream()
                .map(PaymentCancelledPayload.CancelledItem::orderItemId)
                .toList();

            processUseCase.execute(
                new ProcessCancelledItemsUseCase.Command(payload.cancelRequestId(), orderItemIds));

            try {
                Instant cancelledAt = Instant.parse(payload.cancelledAt());
                meterRegistry.timer("cancel.event.e2e.latency")
                    .record(Duration.between(cancelledAt, Instant.now()));
            } catch (DateTimeParseException ignore) { /* 계측 실패는 처리에 영향 없음 */ }

            log.info("payment.cancelled 처리 완료. cancelRequestId={}", payload.cancelRequestId());
            ack.acknowledge();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("중복 처리 감지 (UK 충돌). 멱등 처리로 ack. offset={}", record.offset());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("payment.cancelled 처리 실패. offset={}", record.offset(), e);
            retryRouter.route(record, e);
            ack.acknowledge();
        }
    }
}
