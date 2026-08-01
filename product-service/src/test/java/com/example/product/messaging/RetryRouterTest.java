package com.example.product.messaging;

import com.example.product.application.exception.NonRetryableException;
import com.example.product.application.interfaces.CancelRestoreDlqRepository;
import com.example.product.application.interfaces.OperationAlertPort;
import com.example.product.infrastructure.messaging.RetryRouter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RetryRouter 라우팅 단위테스트 (브로커 불필요 — KafkaTemplate mock, order 관행 정렬 / RST-02).
 */
class RetryRouterTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private CancelRestoreDlqRepository dlqRepository;
    private OperationAlertPort operationAlertPort;
    private RetryRouter retryRouter;

    /** 데이터 오류 표식 테스트용 예외. */
    private static class DataError extends RuntimeException implements NonRetryableException {
        DataError(String msg) { super(msg); }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        // send 는 완료 future 반환(publishToDlq 의 .get(5s) best-effort 확인이 NPE 없이 통과).
        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(null));
        dlqRepository = mock(CancelRestoreDlqRepository.class);
        operationAlertPort = mock(OperationAlertPort.class);
        retryRouter = new RetryRouter(
            kafkaTemplate, new ObjectMapper(), dlqRepository, operationAlertPort,
            "payment.cancelled.retry", "payment.cancelled.DLQ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_retry_when_transient_error_and_retry_count_below_3() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");

        retryRouter.route(record, new RuntimeException("DB timeout"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.retry");
        assertThat(headerValue(captor.getValue(), "retry-count")).isEqualTo("1");
        assertThat(headerValue(captor.getValue(), "original-topic")).isEqualTo("payment.cancelled");
        assertThat(headerValue(captor.getValue(), "first-failed-at")).isNotNull();
        assertThat(headerValue(captor.getValue(), "last-error")).contains("DB timeout");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_dlq_when_transient_error_and_retry_count_reaches_3() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled.retry", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");
        record.headers().add("retry-count", "3".getBytes(StandardCharsets.UTF_8));

        retryRouter.route(record, new RuntimeException("DB timeout"));

        // durable 진실: 테이블 upsert + 알림이 먼저 (leg=STOCK, retryCount=3). cancelRequestId 정확 추출은 IT 에서 검증.
        verify(dlqRepository).upsertPending(anyString(), eq("STOCK"), anyString(), eq(3), anyString());
        verify(operationAlertPort).alert(anyString());
        // 전송로: DLQ 토픽 send 도 유지(best-effort)
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.DLQ");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_publish_to_dlq_immediately_when_data_error() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "payment.cancelled", 0, 100L, "pay_key", "{\"cancelRequestId\":\"cr_1\"}");

        retryRouter.route(record, new DataError("SKU not found"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo("payment.cancelled.DLQ");
    }

    private String headerValue(ProducerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
