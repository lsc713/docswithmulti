package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.OutboxPublisherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox PENDING 건을 Kafka에 발행하는 스케줄러
 * Redis 분산락으로 중복 실행 방지 (10초 주기)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final RedissonClient redissonClient;
    private final OutboxPublisherService outboxPublisherService;

    @Value("${scheduler.lock.outbox-publisher}")
    private String lockKey;

    @Scheduled(fixedDelay = 10_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 9, TimeUnit.SECONDS)) {
                log.debug("[outbox-publisher] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[outbox-publisher] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            outboxPublisherService.publish();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
