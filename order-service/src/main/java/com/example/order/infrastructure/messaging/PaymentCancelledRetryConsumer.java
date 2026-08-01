package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledRetryConsumer {

    private final ProcessCancelledItemsUseCase processUseCase;
    private final RetryRouter retryRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled-retry}",
        groupId = "${spring.kafka.consumer.retry-group-id}",
        containerFactory = "retryKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);
            List<Long> orderItemIds = payload.cancelledItems().stream()
                .map(PaymentCancelledPayload.CancelledItem::orderItemId)
                .toList();

            processUseCase.execute(
                new ProcessCancelledItemsUseCase.Command(payload.cancelRequestId(), orderItemIds));

            log.info("payment.cancelled.retry 처리 완료. cancelRequestId={}", payload.cancelRequestId());
            ack.acknowledge();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("중복 처리 감지 (UK 충돌). 멱등 처리로 ack. offset={}", record.offset());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("payment.cancelled.retry 처리 실패. offset={}", record.offset(), e);
            // LOSS-01: route() 가 예외 없이 반환할 때만 ack. route() 가 던지면 재던져 재전달에 위임.
            try {
                retryRouter.route(record, e);
            } catch (Exception routeEx) {
                log.error("route 실패 — 미ack 로 재전달에 위임. offset={}", record.offset(), routeEx);
                throw routeEx;
            }
            ack.acknowledge();
        }
    }
}
