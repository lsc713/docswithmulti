package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.MarkOrderPaymentCompletedUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedConsumer {

    private final MarkOrderPaymentCompletedUseCase useCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${kafka.topic.payment-completed}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            PaymentCompletedPayload payload =
                objectMapper.readValue(record.value(), PaymentCompletedPayload.class);
            if (payload.orderId() != null && payload.orderId() > 0) {
                useCase.execute(new MarkOrderPaymentCompletedUseCase.Command(payload.orderId()));
            } else {
                log.info("레거시 payment.completed skip. paymentKey={}", payload.paymentKey());
            }
            ack.acknowledge();
        } catch (Exception e) {
            throw new IllegalStateException(
                "payment.completed 처리 실패. offset=" + record.offset(), e);
        }
    }
}
