package com.example.merchantlimit.infrastructure.messaging;

import com.example.merchantlimit.application.interfaces.LimitEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * merchant.limit.updated 아웃박스 폴러. N-인스턴스에서 한 인스턴스만 발행하도록 분산락.
 * payment 스케줄러와 동일한 Redisson RLock 사용. 단 배치(최대 batchSize) 발행 시간이
 * 고정 TTL을 넘길 수 있어 leaseTime 미지정(워치독 자동 리스 갱신)으로 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final LimitEventOutboxRepository outboxRepository;
    private final LimitEventKafkaProducer kafkaProducer;
    private final RedissonClient redissonClient;

    @Value("${outbox.scheduler.batch-size:1000}")
    private int batchSize;

    @Value("${outbox.scheduler.lock-key}")
    private String lockKey;

    @Scheduled(fixedDelay = 10_000)
    public void publish() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // waitTime 0 + leaseTime 미지정 → 획득 못 하면 즉시 skip, 보유 중엔 워치독이 리스 자동연장.
            if (!lock.tryLock(0, TimeUnit.SECONDS)) {
                log.debug("Outbox 스케줄러 락 획득 실패 — 다른 인스턴스 실행 중");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            List<LimitEventOutboxRepository.PendingOutbox> pending =
                outboxRepository.findPendingBatch(batchSize);

            for (LimitEventOutboxRepository.PendingOutbox outbox : pending) {
                try {
                    kafkaProducer.publish(outbox.merchantId(), outbox.payload());
                    outboxRepository.markPublished(outbox.id());
                } catch (Exception e) {
                    log.error("Outbox 발행 실패. outboxId={}", outbox.id(), e);
                }
            }

            if (!pending.isEmpty()) {
                log.info("Outbox 발행 완료. count={}", pending.size());
            }
        } finally {
            // 소유권 확인 후 해제 — 리스 만료로 다른 인스턴스가 이미 잡았다면 그 락을 지우지 않는다.
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
