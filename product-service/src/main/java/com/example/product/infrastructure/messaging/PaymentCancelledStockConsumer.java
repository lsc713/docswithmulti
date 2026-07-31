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
 * payment.cancelled 구독 → 취소된 SKU 재고 복원 (RST-02, D-P3-2).
 *
 * <p>cancelledItems 중 skuId != null 인 항목만 release 대상(하위호환: skuId 없는 과거 건 제외).
 * 멱등 테이블(processed_cancel_event)·부분취소 하드닝은 03-02, retry/DLQ 는 03-03 범위 —
 * 여기서는 happy path + UK 충돌 멱등 ack(order 패턴, 03-02 대비 미리 포함)만 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledStockConsumer {

    private final ProcessCancelledStockUseCase processUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);

            List<Command.Item> items = payload.cancelledItems().stream()
                .filter(ci -> ci.skuId() != null) // 하위호환: skuId 없는 항목은 재고 복원 대상 아님
                .map(ci -> new Command.Item(ci.skuId(), ci.quantity()))
                .toList();

            processUseCase.execute(
                new Command(payload.cancelRequestId(), payload.paymentKey(), items));

            log.info("payment.cancelled 재고 복원 완료. cancelRequestId={}, items={}",
                payload.cancelRequestId(), items.size());
            ack.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("중복 처리 감지 (UK 충돌). 멱등 처리로 ack. offset={}", record.offset());
            ack.acknowledge();
        }
    }
}
