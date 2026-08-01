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
 * 멱등 테이블(processed_cancel_event)은 03-02. retry/DLQ(03-03): 처리 실패 시 RetryRouter 로
 * 라우팅(일시적 오류 → retry, 데이터오류/3회초과 → DLQ)하고, offset ack 는 항상 마지막에 수행한다
 * (성공·retry·DLQ 이동 후에만 커밋 — 메시지 유실 없음, order 패턴 동형).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelledStockConsumer {

    private final ProcessCancelledStockUseCase processUseCase;
    private final RetryRouter retryRouter;
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
        } catch (Exception e) {
            log.error("payment.cancelled 재고 복원 실패. offset={}", record.offset(), e);
            // LOSS-01: route() 가 예외 없이 반환할 때만 ack. route() 가 던지면(재발행 브로커 미확인)
            // 재던져 컨테이너 에러핸들러가 재전달하게 한다(원본 미ack → 유실 없음).
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
