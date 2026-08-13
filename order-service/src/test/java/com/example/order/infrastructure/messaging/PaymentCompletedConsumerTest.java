package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.MarkOrderPaymentCompletedUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.*;

class PaymentCompletedConsumerTest {

    private MarkOrderPaymentCompletedUseCase useCase;
    private PaymentCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        useCase = mock(MarkOrderPaymentCompletedUseCase.class);
        consumer = new PaymentCompletedConsumer(useCase, new ObjectMapper());
    }

    @Test
    void valid_completed_event_executes_and_acknowledges() {
        Acknowledgment ack = mock(Acknowledgment.class);
        var record = record(completedPayload("\"orderId\":7,"));

        consumer.consume(record, ack);

        verify(useCase).execute(new MarkOrderPaymentCompletedUseCase.Command(7L));
        verify(ack).acknowledge();
    }

    @Test
    void legacy_event_without_order_id_is_acknowledged_without_execution() {
        Acknowledgment ack = mock(Acknowledgment.class);
        var record = record(completedPayload(""));

        consumer.consume(record, ack);

        verifyNoInteractions(useCase);
        verify(ack).acknowledge();
    }

    @Test
    void invalid_json_is_not_acknowledged() {
        Acknowledgment ack = mock(Acknowledgment.class);
        var record = record("not-json");

        try {
            consumer.consume(record, ack);
        } catch (IllegalStateException expected) {
            // 컨테이너의 기존 무제한 재전달 정책에 맡긴다.
        }

        verifyNoInteractions(useCase, ack);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("payment.completed", 0, 1L, "pay_1", value);
    }

    private String completedPayload(String orderIdField) {
        return "{\"paymentKey\":\"pay_1\"," + orderIdField
            + "\"merchantId\":1,\"totalAmount\":10000,"
            + "\"items\":[{\"paymentItemId\":10,\"itemAmount\":10000}],"
            + "\"completedAt\":\"2026-08-13T00:00:00Z\"}";
    }
}
