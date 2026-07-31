package com.example.product.infrastructure.messaging;

import com.example.product.application.usecase.ProcessCancelledStockUseCase;
import com.example.product.application.usecase.ProcessCancelledStockUseCase.Command;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * payment.cancelled.retry 구독 → 실패했던 재고 복원 재시도 (RST-02, D-P3-2, order 복제).
 *
 * <p>메인 consumer 와 동일한 useCase 를 실행하고, 재실패 시 다시 {@link RetryRouter#route}
 * (retry-count 증가 → 3회 초과 시 DLQ). UK 충돌은 멱등 ack. ack 는 항상 마지막.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledStockRetryConsumer {

    private final ProcessCancelledStockUseCase processUseCase;
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

            List<Command.Item> items = payload.cancelledItems().stream()
                .filter(ci -> ci.skuId() != null)
                .map(ci -> new Command.Item(ci.skuId(), ci.quantity()))
                .toList();

            processUseCase.execute(
                new Command(payload.cancelRequestId(), payload.paymentKey(), items));

            log.info("payment.cancelled.retry 재고 복원 완료. cancelRequestId={}, items={}",
                payload.cancelRequestId(), items.size());
            ack.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("중복 처리 감지 (UK 충돌). 멱등 처리로 ack. offset={}", record.offset());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("payment.cancelled.retry 재고 복원 실패. offset={}", record.offset(), e);
            retryRouter.route(record, e);
            ack.acknowledge();
        }
    }
}
