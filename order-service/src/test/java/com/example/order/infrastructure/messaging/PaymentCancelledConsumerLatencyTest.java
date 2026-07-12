package com.example.order.infrastructure.messaging;

import com.example.order.application.usecase.ProcessCancelledItemsUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class PaymentCancelledConsumerLatencyTest {

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    @Mock ProcessCancelledItemsUseCase processUseCase;
    @Mock RetryRouter retryRouter;
    ObjectMapper objectMapper = new ObjectMapper();
    PaymentCancelledConsumer consumer;

    @BeforeEach void setUp() {
        consumer = new PaymentCancelledConsumer(processUseCase, retryRouter, objectMapper, registry);
    }

    @Test
    @DisplayName("정상 소비 시 cancel.event.e2e.latency 타이머 1건 기록")
    void records_latency() {
        String cancelledAt = Instant.now().minusSeconds(2).toString();
        String json = "{\"cancelRequestId\":\"5\",\"paymentKey\":\"p\",\"merchantId\":1," +
                      "\"cancelledItems\":[],\"cancelledAt\":\"" + cancelledAt + "\"}";
        var record = new ConsumerRecord<>("payment.cancelled", 0, 0L, "5", json);
        consumer.consume(record, mock(Acknowledgment.class));
        assertThat(registry.get("cancel.event.e2e.latency").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cancelledAt 파싱 실패 시 메트릭 미기록, 메시지 처리는 정상 완료")
    void skips_metric_on_invalid_cancelledAt() {
        String json = "{\"cancelRequestId\":\"6\",\"paymentKey\":\"p\",\"merchantId\":1," +
                      "\"cancelledItems\":[],\"cancelledAt\":\"not-a-valid-instant\"}";
        var record = new ConsumerRecord<>("payment.cancelled", 0, 1L, "6", json);
        Acknowledgment ack = mock(Acknowledgment.class);
        // Should not throw; ack is called
        consumer.consume(record, ack);
        // Timer should not have been registered
        assertThat(registry.getMeters().stream()
            .anyMatch(m -> m.getId().getName().equals("cancel.event.e2e.latency")))
            .isFalse();
    }
}
