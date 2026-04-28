package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.ProcessingRecoveryService;
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
public class ProcessingRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final ProcessingRecoveryService processingRecoveryService;

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
            processingRecoveryService.recoverAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
