package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.PendingRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final PendingRecoveryService pendingRecoveryService;

    @Value("${scheduler.lock.pending-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[pending-recovery] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[pending-recovery] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            pendingRecoveryService.recoverAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
