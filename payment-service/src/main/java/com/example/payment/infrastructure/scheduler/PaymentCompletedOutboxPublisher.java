package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.OperationAlertPort;
import com.example.payment.application.interfaces.PaymentEventOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * payment.completed 아웃박스 폴 발행기 (CancelEventOutboxPublisher 동형, poll-only).
 *
 * <p>cancel 발행기에서 subscribeWake/triggerPoll(wake relay)과 purge() 를 뺀 순수 폴 버전.
 * 파티션 키 = paymentKey. RLock 으로 한 인스턴스만 발행. cancel.* / cancel.publish.mode 프로퍼티에
 * 절대 결합하지 않는다(독립 키만 사용).
 *
 * <p>// ponytail: poll-only main-pool publisher; add wake-relay + purge only if publish p99 or outbox retention matters.
 */
@Slf4j
@Component
public class PaymentCompletedOutboxPublisher {

    private final PaymentEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedissonClient redissonClient;
    private final OperationAlertPort operationAlertPort;

    @Value("${kafka.topic.payment-completed}")
    private String topic;

    @Value("${scheduler.lock.payment-completed-outbox}")
    private String lockKey;

    @Value("${payment.completed.outbox.batch-size:1000}")
    private int batchSize;

    @Value("${payment.completed.outbox.max-retries:10}")
    private int maxRetries;

    public PaymentCompletedOutboxPublisher(
            PaymentEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            RedissonClient redissonClient,
            OperationAlertPort operationAlertPort) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redissonClient = redissonClient;
        this.operationAlertPort = operationAlertPort;
    }

    @Scheduled(fixedDelayString = "${payment.completed.outbox.poll-ms:10000}")
    public void publish() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[payment-completed-outbox] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[payment-completed-outbox] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<PaymentEventOutboxRepository.PendingOutbox> pending =
                outboxRepository.findPendingBatch(batchSize);

            // 1) 전 건 send 를 먼저 발사 — 프로듀서 내부 배칭·병렬 전송.
            List<InFlight> inFlight = new ArrayList<>(pending.size());
            for (var o : pending) {
                inFlight.add(new InFlight(o.id(), o.retryCount(),
                        kafkaTemplate.send(topic, o.paymentKey(), o.payload())));
            }

            // 2) ack 일괄 대기 — 성공/실패 분기
            List<Long> published = new ArrayList<>();
            for (var s : inFlight) {
                try {
                    s.future().get(30, TimeUnit.SECONDS);
                    published.add(s.id());
                } catch (Exception e) {
                    String err = e.getMessage();
                    if (s.retryCount() + 1 >= maxRetries) {
                        outboxRepository.markDead(s.id(), err);
                        operationAlertPort.alert(
                                "[payment-completed-outbox] 발행 영구 실패(DEAD) outboxId=" + s.id() + " err=" + err);
                        log.error("[payment-completed-outbox] DEAD 전이 outboxId={}", s.id(), e);
                    } else {
                        outboxRepository.bumpRetry(s.id(), err);
                        log.warn("[payment-completed-outbox] 발행 실패 재시도 예정 outboxId={} retry={}",
                                s.id(), s.retryCount() + 1);
                    }
                }
            }

            outboxRepository.markPublished(published); // 성공분 한 번의 UPDATE 로 PUBLISHED
            if (!pending.isEmpty()) {
                log.info("[payment-completed-outbox] 발행 완료. count={}/{}", published.size(), pending.size());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private record InFlight(long id, int retryCount, CompletableFuture<SendResult<String, String>> future) {}
}
