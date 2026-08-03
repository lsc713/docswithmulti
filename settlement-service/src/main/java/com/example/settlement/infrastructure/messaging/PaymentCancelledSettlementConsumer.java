package com.example.settlement.infrastructure.messaging;

import com.example.settlement.application.usecase.RecordCancellationUseCase;
import com.example.settlement.application.usecase.RecordCancellationUseCase.Command;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * payment.cancelled 구독 → 정산 원장 CANCEL 라인 적재 (CANCEL-01/02).
 *
 * <p>Phase 1: 단순 ack + 멱등. UK 충돌(DataIntegrityViolationException)은 중복으로 보고 ack(no-op).
 * 그 외 예외는 재던져 DefaultErrorHandler(FixedBackOff)가 재전달 — RetryRouter 없음(리컨실이 Phase 3 backstop).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledSettlementConsumer {

    private final RecordCancellationUseCase useCase;
    private final ObjectMapper objectMapper;   // tools.jackson.databind.ObjectMapper (Jackson 3, Boot 4 default)

    @KafkaListener(
        topics = "${kafka.topic.payment-cancelled}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCancelledPayload payload =
                objectMapper.readValue(record.value(), PaymentCancelledPayload.class);

            // CANCEL amount = Σ cancelledItems[].itemAmount (raw 합, 반올림은 Phase 2)
            BigDecimal cancelAmount = payload.cancelledItems().stream()
                .map(PaymentCancelledPayload.CancelledItem::itemAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            useCase.record(new Command(
                payload.cancelRequestId(),
                payload.paymentKey(),
                payload.merchantId(),
                cancelAmount,
                Instant.parse(payload.cancelledAt())));

            log.info("settlement CANCEL 적재 완료. cancelRequestId={}, amount={}",
                payload.cancelRequestId(), cancelAmount);
            ack.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("중복 이벤트 (event_id UK) — 멱등 ack. offset={}", record.offset());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("settlement 적재 실패. offset={}", record.offset(), e);
            throw e; // DefaultErrorHandler 재전달 — 미ack 원본 유실 없음
        }
    }
}
