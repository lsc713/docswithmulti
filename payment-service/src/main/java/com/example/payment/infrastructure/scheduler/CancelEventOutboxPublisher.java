package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.interfaces.CancelEventOutboxRepository;
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

/** OUTBOX 모드에서만 활성. RLock으로 한 인스턴스만 폴링 발행. */
@Slf4j
@Component
@ConditionalOnProperty(name = "cancel.publish.mode", havingValue = "OUTBOX")
public class CancelEventOutboxPublisher {

    private final CancelEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedissonClient redissonClient;

    @Value("${kafka.topic.payment-cancelled}")
    private String topic;

    @Value("${scheduler.lock.cancel-outbox-publisher}")
    private String lockKey;

    @Value("${cancel.outbox.batch-size:1000}")
    private int batchSize;

    public CancelEventOutboxPublisher(
            CancelEventOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            RedissonClient redissonClient) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.redissonClient = redissonClient;
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
                inFlight.add(new InFlight(o.id(),
                        kafkaTemplate.send(topic, String.valueOf(o.cancelRequestId()), o.payload())));
            }

            // 2) ack 를 일괄 대기 — 성공 id 만 수집(per-row 실패 격리 유지).
            List<Long> published = new ArrayList<>(inFlight.size());
            for (var s : inFlight) {
                try {
                    s.future().get(30, TimeUnit.SECONDS);
                    published.add(s.id());
                } catch (Exception e) {
                    log.error("[outbox] 발행 실패 (다음 폴 재시도). outboxId={}", s.id(), e);
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

    /** 발사한 send 의 outbox id ↔ ack future 짝. */
    private record InFlight(long id, CompletableFuture<SendResult<String, String>> future) {}
}
