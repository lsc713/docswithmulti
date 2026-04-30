package com.example.payment.application.service;

import com.example.payment.application.interfaces.FailedKafkaEventRepository;
import com.example.payment.application.interfaces.FailedKafkaEventRepository.PendingFailedEvent;
import com.example.payment.application.interfaces.OperationAlertPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailedKafkaPublisherService {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;

    private final FailedKafkaEventRepository repo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OperationAlertPort alertPort;

    public void publish() {
        List<PendingFailedEvent> pending = repo.findPendingBatch(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.info("[failed-kafka-publisher] 재시도 대상 {}건", pending.size());

        for (PendingFailedEvent event : pending) {
            try {
                kafkaTemplate.send(event.topic(), String.valueOf(event.cancelRequestId()), event.payload())
                    .get(5, TimeUnit.SECONDS);
                repo.markPublished(event.cancelRequestId());
                log.debug("[failed-kafka-publisher] 재발행 성공. cancelRequestId={}", event.cancelRequestId());
            } catch (Exception e) {
                int nextRetry = event.retryCount() + 1;
                String error = e.getMessage();
                if (nextRetry >= MAX_RETRIES) {
                    repo.markExhausted(event.cancelRequestId(), error);
                    alertPort.alert(String.format(
                        "[failed-kafka-publisher] EXHAUSTED. cancelRequestId=%d, error=%s",
                        event.cancelRequestId(), error));
                    log.error("[failed-kafka-publisher] EXHAUSTED. cancelRequestId={}", event.cancelRequestId(), e);
                } else {
                    repo.incrementRetry(event.cancelRequestId(), error);
                    log.warn("[failed-kafka-publisher] 재발행 실패({}/{}). cancelRequestId={}",
                        nextRetry, MAX_RETRIES, event.cancelRequestId());
                }
            }
        }
    }
}
