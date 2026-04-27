package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.CompensationRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * compensation_retry 보상 재시도 스케줄러
 * Redis 분산락으로 중복 실행 방지 (30초 주기)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationRetryScheduler {

    private final RedissonClient redissonClient;
    private final CompensationRetryService compensationRetryService;

    @Value("${scheduler.lock.compensation-retry}")
    private String lockKey;

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) {
                log.debug("[compensation-retry] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[compensation-retry] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            compensationRetryService.retryAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
