package com.example.settlement.infrastructure.messaging;

import com.example.settlement.application.usecase.RecordSaleUseCase;
import com.example.settlement.application.usecase.RecordSaleUseCase.Command;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * payment.completed 구독 → 정산 원장 SALE 라인 적재 (SALE-01/02). PaymentCancelledSettlementConsumer 동형.
 *
 * <p>기존 kafkaListenerContainerFactory 에 붙는 2번째 리스너(같은 groupId=settlement-service).
 * UK 충돌(DataIntegrityViolationException)은 중복으로 보고 ack(no-op). 그 외 예외는 재던져
 * DefaultErrorHandler(FixedBackOff)가 재전달 — 리컨실이 Phase 3 backstop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedSettlementConsumer {

    private final RecordSaleUseCase useCase;
    private final ObjectMapper objectMapper;   // tools.jackson.databind.ObjectMapper (Jackson 3, Boot 4 default)

    @KafkaListener(
        topics = "${kafka.topic.payment-completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCompletedPayload payload =
                objectMapper.readValue(record.value(), PaymentCompletedPayload.class);

            // gross = totalAmount (Σ items[].itemAmount, ×quantity 아님)
            useCase.record(new Command(
                payload.paymentKey(),
                payload.merchantId(),
                payload.totalAmount(),
                Instant.parse(payload.completedAt())));

            log.info("settlement SALE 적재 완료. paymentKey={}, gross={}",
                payload.paymentKey(), payload.totalAmount());
            ack.acknowledge();
        } catch (DataIntegrityViolationException e) {
            log.info("중복 이벤트 (event_id UK) — 멱등 ack. offset={}", record.offset());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("settlement SALE 적재 실패. offset={}", record.offset(), e);
            throw e; // DefaultErrorHandler 재전달 — 미ack 원본 유실 없음
        }
    }
}
