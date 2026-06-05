package com.example.product.infrastructure.messaging;

import com.example.product.application.usecase.StockRestoreUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancelledStockConsumer")
class PaymentCancelledStockConsumerTest {

    @Mock StockRestoreUseCase stockRestoreUseCase;
    @Mock Acknowledgment acknowledgment;
    private PaymentCancelledStockConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentCancelledStockConsumer(stockRestoreUseCase, new ObjectMapper());
    }

    @Test
    @DisplayName("정상 메시지 처리 → ACK")
    void shouldProcessAndAck() {
        String payload = """
            {"cancelRequestId":1,"cancelledItems":[{"skuId":5,"quantity":2}]}
            """;
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.cancelled", 0, 0, "key", payload);
        consumer.consume(record, acknowledgment);
        verify(stockRestoreUseCase).execute(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("JSON 파싱 실패 → ACK (skip)")
    void shouldAckOnParseError() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.cancelled", 0, 0, "key", "invalid");
        consumer.consume(record, acknowledgment);
        verify(stockRestoreUseCase, never()).execute(any());
        verify(acknowledgment).acknowledge();
    }
}
