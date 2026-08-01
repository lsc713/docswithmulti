package com.example.order.infrastructure.messaging;

import com.example.order.application.exception.NonRetryableException;
import com.example.order.application.interfaces.CancelRestoreDlqRepository;
import com.example.order.application.interfaces.OperationAlertPort;
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

/**
 * consumer 처리 실패 라우팅 (Phase 1 product RetryRouter 미러, LOSS-01/DLQ-01/DLQ-02).
 *
 * <p>일시적 오류 + retry-count &lt; 3 → payment.cancelled.retry 로 재발행(재시도, 동기확인은 Task 2).
 * 데이터 오류({@link NonRetryableException}) 또는 retry-count &gt;= 3 → durable cancel_restore_dlq 적재 + 알림.
 *
 * <p>order 는 {@code KafkaProducerConfig} 의 @Bean 으로 배선(RetryRouter 는 @Component 아님).
 */
@Slf4j
public class RetryRouter {

    private static final int MAX_RETRY_COUNT = 3;
    /** cancel_restore_dlq.leg — order 레그 식별(관측성). */
    private static final String LEG = "ORDER";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CancelRestoreDlqRepository cancelRestoreDlqRepository;
    private final OperationAlertPort operationAlertPort;
    private final String retryTopic;
    private final String dlqTopic;

    public RetryRouter(KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper,
                       CancelRestoreDlqRepository cancelRestoreDlqRepository,
                       OperationAlertPort operationAlertPort,
                       String retryTopic, String dlqTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.cancelRestoreDlqRepository = cancelRestoreDlqRepository;
        this.operationAlertPort = operationAlertPort;
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

    /**
     * DLQ 경로 (Phase 1 product 미러). <b>durable 진실 = cancel_restore_dlq 테이블</b> — 먼저 적재+알림한다.
     *
     * <p>그 다음 payment.cancelled.DLQ 토픽 send 는 전송로로 유지하되 <b>best-effort</b>:
     * 이 토픽은 소비자가 0개이고 durable 진실은 방금 쓴 테이블이므로,
     * send 실패를 rethrow 하면 이미 durable 기록됐는데도 원본 미ack → 무한 재전달 → 파티션 stall.
     * 따라서 {@code .get(5s)} 로 확인은 하되 실패해도 로그/alert 만 남기고 삼킨다(예외 전파 금지).
     * 재구동 스케줄러가 테이블에서 복구한다.
     */
    private void publishToDlq(ConsumerRecord<String, String> record, int retryCount, Exception e) {
        String cancelRequestId = extractCancelRequestId(record);
        String lastError = DlqMessage.truncate(e.getMessage(), 512);

        // 1) durable 진실 — 멱등 적재 + 알림 (send 이전, 손실 0 근거)
        cancelRestoreDlqRepository.upsertPending(cancelRequestId, LEG, record.value(), retryCount, lastError);
        operationAlertPort.alert("[cancel-restore][" + LEG + "] DLQ 적재 cancelRequestId="
            + cancelRequestId + " err=" + e.getMessage());
        log.error("DLQ 테이블 적재. leg={}, cancelRequestId={}, retryCount={}, offset={}, error={}",
            LEG, cancelRequestId, retryCount, record.offset(), e.getMessage());

        // 2) 미소비 DLQ 토픽 send — best-effort(실패 삼킴, rethrow 금지 → 파티션 stall 방지)
        try {
            String dlqPayload = objectMapper.writeValueAsString(DlqMessage.of(record, retryCount, e));
            ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, record.key(), dlqPayload);
            kafkaTemplate.send(dlqRecord).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            // durable 테이블에 이미 기록됨 → 토픽 send 실패는 삼킨다(로그만).
            log.warn("DLQ 토픽 send 실패(무시 — durable 테이블이 진실). offset={}", record.offset(), ex);
        }
    }

    /** payload(JSON)에서 cancelRequestId 추출. 파싱 실패 시 원본 key 로 폴백(적재/멱등은 유지). */
    private String extractCancelRequestId(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), PaymentCancelledPayload.class).cancelRequestId();
        } catch (Exception ex) {
            log.warn("cancelRequestId 파싱 실패 — key 로 폴백. offset={}", record.offset(), ex);
            return record.key();
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
