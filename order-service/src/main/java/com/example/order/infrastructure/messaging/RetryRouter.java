package com.example.order.infrastructure.messaging;

import com.example.order.application.exception.NonRetryableException;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
public class RetryRouter {

    private static final int MAX_RETRY_COUNT = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String retryTopic;
    private final String dlqTopic;

    public RetryRouter(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                       String retryTopic, String dlqTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.retryTopic = retryTopic;
        this.dlqTopic = dlqTopic;
    }

    public void route(ConsumerRecord<String, String> record, Exception e) {
        int retryCount = parseRetryCount(record);
        if (isDataError(e) || retryCount >= MAX_RETRY_COUNT) {
            publishToDlq(record, retryCount, e);
        } else {
            publishToRetry(record, retryCount, e);
        }
    }

    private boolean isDataError(Exception e) {
        return e instanceof NonRetryableException;
    }

    private void publishToRetry(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        int newRetryCount = retryCount + 1;
        Instant now = Instant.now();
        String firstFailedAt = retryCount == 0
            ? now.toString()
            : headerStringValue(record, "first-failed-at").orElse(now.toString());

        ProducerRecord<String, String> retryRecord = new ProducerRecord<>(retryTopic, record.key(), record.value());
        retryRecord.headers()
            .add("retry-count", String.valueOf(newRetryCount).getBytes(StandardCharsets.UTF_8))
            .add("next-retry-at", now.plus(retryDelay(newRetryCount)).toString().getBytes(StandardCharsets.UTF_8))
            .add("original-topic", record.topic().getBytes(StandardCharsets.UTF_8))
            .add("first-failed-at", firstFailedAt.getBytes(StandardCharsets.UTF_8))
            .add("last-error", DlqMessage.truncate(e.getMessage(), 200).getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(retryRecord);
        log.warn("retry 토픽 발행. retryCount={}, offset={}", newRetryCount, record.offset());
    }

    private void publishToDlq(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        try {
            String dlqPayload = objectMapper.writeValueAsString(DlqMessage.of(record, retryCount, e));
            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, record.key(), dlqPayload);
            kafkaTemplate.send(dlqRecord);
            log.error("DLQ 발행. retryCount={}, offset={}, error={}", retryCount, record.offset(), e.getMessage());
        } catch (Exception ex) {
            log.error("DLQ 발행 실패. offset={}", record.offset(), ex);
        }
    }

    private int parseRetryCount(ConsumerRecord<String, String> record) {
        return headerStringValue(record, "retry-count").map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException ex) {
                log.warn("retry-count 헤더 파싱 실패, 0으로 처리: {}", v);
                return 0;
            }
        }).orElse(0);
    }

    private Optional<String> headerStringValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return Optional.empty();
        return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    private Duration retryDelay(int retryCount) {
        return switch (retryCount) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(10);
        };
    }
}
