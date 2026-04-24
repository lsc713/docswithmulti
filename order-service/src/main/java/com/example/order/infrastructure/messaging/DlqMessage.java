package com.example.order.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

public record DlqMessage(String originalMessage, DlqMeta dlqMeta) {

    public record DlqMeta(
        String originalTopic,
        int originalPartition,
        long originalOffset,
        int retryCount,
        String firstFailedAt,
        String lastFailedAt,
        String lastError,
        String movedToDlqAt
    ) {}

    public static DlqMessage of(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        Instant now = Instant.now();
        String firstFailedAt = headerValue(record, "first-failed-at").orElse(now.toString());
        return new DlqMessage(
            record.value(),
            new DlqMeta(
                record.topic(),
                record.partition(),
                record.offset(),
                retryCount,
                firstFailedAt,
                now.toString(),
                truncate(e.getMessage(), 200),
                now.toString()
            )
        );
    }

    private static Optional<String> headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) return Optional.empty();
        return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    static String truncate(String s, int max) {
        if (s == null) return "unknown";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
