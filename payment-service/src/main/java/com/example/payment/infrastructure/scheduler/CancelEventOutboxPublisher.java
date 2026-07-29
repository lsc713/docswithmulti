package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
import com.example.payment.application.interfaces.OperationAlertPort;
import com.example.payment.infrastructure.messaging.OutboxCancelEventPublisher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** OUTBOX 모드에서만 활성. RLock으로 한 인스턴스만 폴링 발행. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX", matchIfMissing = true)
public class CancelEventOutboxPublisher {

    private final CancelEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedissonClient redissonClient;
    private final OperationAlertPort operationAlertPort;

    @Value("${kafka.topic.payment-cancelled}")
    private String topic;

    @Value("${scheduler.lock.cancel-outbox-publisher}")
    private String lockKey;

    @Value("${cancel.outbox.batch-size:1000}")
    private int batchSize;

    @Value("${cancel.outbox.max-retries:10}")
    private int maxRetries;

    @Value("${cancel.outbox.retention-days:7}")
    private int retentionDays;

    @Value("${scheduler.lock.cancel-outbox-purge}")
    private String purgeLockKey;

    /** wake 경로 폴 폭주 방지 — 폴 진행 중 추가 wake는 skip(coalesce). @Scheduled backstop과는 무관. */
    private final AtomicBoolean pollScheduled = new AtomicBoolean(false);

    public CancelEventOutboxPublisher(
            CancelEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            RedissonClient redissonClient,
            OperationAlertPort operationAlertPort) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redissonClient = redissonClient;
        this.operationAlertPort = operationAlertPort;
    }

    /** OutboxCancelEventPublisher가 커밋 후 발사하는 wake를 구독 — 즉시 coalesced 폴 트리거(저지연). */
    @PostConstruct
    void subscribeWake() {
        redissonClient.getTopic(OutboxCancelEventPublisher.WAKE_TOPIC)
                .addListener(Long.class, (channel, msg) -> triggerPoll());
    }

    /** wake 경로 진입점. 폴 진행 중이면 skip(coalesce) — 둘 다 결국 publish()의 RLock으로 단일 발행 보장. */
    private void triggerPoll() {
        if (!pollScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            publish();
        } finally {
            pollScheduled.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${cancel.outbox.poll-ms:10000}")
    public void publish() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[outbox] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[outbox] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<CancelEventOutboxRepository.PendingOutbox> pending = outboxRepository.findPendingBatch(batchSize);

            // 1) 전 건 send 를 먼저 발사 — send()는 즉시 future 반환(프로듀서가 내부 배칭·병렬 전송).
            //    순차 .get() 상한(건당 RTT×N)을 제거하고 전송을 겹친다.
            List<InFlight> inFlight = new ArrayList<>(pending.size());
            for (var o : pending) {
                inFlight.add(new InFlight(o.id(), o.retryCount(),
                        kafkaTemplate.send(topic, String.valueOf(o.cancelRequestId()), o.payload())));
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
                                "[outbox] 발행 영구 실패(DEAD) outboxId=" + s.id() + " err=" + err);
                        log.error("[outbox] DEAD 전이 outboxId={}", s.id(), e);
                    } else {
                        outboxRepository.bumpRetry(s.id(), err);
                        log.warn("[outbox] 발행 실패 재시도 예정 outboxId={} retry={}", s.id(), s.retryCount() + 1);
                    }
                }
            }

            outboxRepository.markPublished(published); // 성공분 한 번의 UPDATE 로 PUBLISHED (커넥션 1회)
            if (!pending.isEmpty()) {
                log.info("[outbox] 발행 완료. count={}/{}", published.size(), pending.size());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** PUBLISHED 보존기간(retentionDays) 초과 행 삭제. 발행 락과 별도 락 사용 — 서로 경합하지 않음. */
    @Scheduled(fixedDelayString = "${cancel.outbox.purge-ms:86400000}")
    public void purge() {
        RLock lock = redissonClient.getLock(purgeLockKey);
        try {
            if (!lock.tryLock(0, 300, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            int deleted = outboxRepository.purgePublished(retentionDays);
            if (deleted > 0) {
                log.info("[outbox-purge] 삭제 {}건 (보존 {}일)", deleted, retentionDays);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 발사한 send 의 outbox id ↔ 재시도 횟수 ↔ ack future 짝. */
    private record InFlight(long id, int retryCount, CompletableFuture<SendResult<String, String>> future) {}
}
