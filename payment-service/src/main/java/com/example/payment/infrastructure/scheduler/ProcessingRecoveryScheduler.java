package com.example.payment.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * CancelRequest PROCESSING 5분 초과 복구 스케줄러
 * Redis 분산락으로 중복 실행 방지 (60초 주기)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingRecoveryScheduler {

    private final RedissonClient redissonClient;

    @Value("${scheduler.lock.processing-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[processing-recovery] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[processing-recovery] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            // TODO: CancelRequest PROCESSING 5분 초과 복구
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
