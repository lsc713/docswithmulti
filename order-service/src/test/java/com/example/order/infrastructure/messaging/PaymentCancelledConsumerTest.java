package com.example.order.infrastructure.messaging;

import com.example.order.application.exception.OrderItemNotFoundException;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentCancelledConsumerTest {

    private ProcessCancelledItemsUseCase processUseCase;
    private RetryRouter retryRouter;
    private ObjectMapper objectMapper;
    private PaymentCancelledConsumer consumer;
    private PaymentCancelledRetryConsumer retryConsumer;

    @BeforeEach
    void setUp() {
        processUseCase = mock(ProcessCancelledItemsUseCase.class);
        retryRouter = mock(RetryRouter.class);
        objectMapper = new ObjectMapper();
        consumer = new PaymentCancelledConsumer(processUseCase, retryRouter, objectMapper, new SimpleMeterRegistry());
        retryConsumer = new PaymentCancelledRetryConsumer(processUseCase, retryRouter, objectMapper);
    }

    // Helper
    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("payment.cancelled", 0, 100L, "pay_key", value);
    }

    private ConsumerRecord<String, String> retryRecord(String value) {
        return new ConsumerRecord<>("payment.cancelled.retry", 0, 100L, "pay_key", value);
    }

    private String validPayload(String cancelRequestId, long orderItemId) {
        return String.format(
            "{\"cancelRequestId\":\"%s\",\"paymentKey\":\"pk_1\",\"merchantId\":1," +
            "\"cancelledItems\":[{\"paymentItemId\":1,\"orderItemId\":%d,\"itemAmount\":\"10000\"}]," +
            "\"cancelledAt\":\"2026-04-24T10:00:00Z\"}", cancelRequestId, orderItemId);
    }

    @Test
    void consumer_should_execute_usecase_and_ack_on_success() {
        Acknowledgment ack = mock(Acknowledgment.class);
        consumer.consume(record(validPayload("cr_1", 10L)), ack);

        verify(processUseCase).execute(
            new ProcessCancelledItemsUseCase.Command("cr_1", List.of(10L)));
        verify(ack).acknowledge();
        verifyNoInteractions(retryRouter);
    }

    @Test
    void consumer_should_route_and_ack_on_invalid_json() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> badRecord = record("not-json");

        consumer.consume(badRecord, ack);

        verify(retryRouter).route(eq(badRecord), any(Exception.class));
        verify(ack).acknowledge();
        verifyNoInteractions(processUseCase);
    }

    @Test
    void consumer_should_route_and_ack_when_usecase_throws() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> rec = record(validPayload("cr_2", 99L));
        doThrow(new OrderItemNotFoundException(List.of(99L)))
            .when(processUseCase).execute(any());

        consumer.consume(rec, ack);

        verify(retryRouter).route(eq(rec), any(OrderItemNotFoundException.class));
        verify(ack).acknowledge();
    }

    @Test
    void retry_consumer_should_execute_usecase_and_ack_on_success() {
        Acknowledgment ack = mock(Acknowledgment.class);
        retryConsumer.consume(retryRecord(validPayload("cr_3", 20L)), ack);

        verify(processUseCase).execute(
            new ProcessCancelledItemsUseCase.Command("cr_3", List.of(20L)));
        verify(ack).acknowledge();
        verifyNoInteractions(retryRouter);
    }

    @Test
    void retry_consumer_should_route_and_ack_when_usecase_throws() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> rec = retryRecord(validPayload("cr_4", 99L));
        doThrow(new RuntimeException("DB timeout")).when(processUseCase).execute(any());

        retryConsumer.consume(rec, ack);

        verify(retryRouter).route(eq(rec), any(RuntimeException.class));
        verify(ack).acknowledge();
    }

    @Test
    void consumer_should_ack_without_retry_on_duplicate_key_violation() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> rec = record(validPayload("cr_dup", 10L));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("UK constraint violated"))
            .when(processUseCase).execute(any());

        consumer.consume(rec, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(retryRouter);
    }
}
