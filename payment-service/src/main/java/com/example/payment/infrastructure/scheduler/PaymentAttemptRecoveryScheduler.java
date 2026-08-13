package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.PaymentAttemptService;
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
public class PaymentAttemptRecoveryScheduler {
    private final RedissonClient redissonClient;
    private final PaymentAttemptService paymentAttemptService;

    @Value("${scheduler.lock.payment-attempt-recovery}") private String lockKey;

    @Scheduled(fixedDelayString = "${payment.attempt.recovery.poll-ms:30000}")
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            paymentAttemptService.recoverPending();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
}
