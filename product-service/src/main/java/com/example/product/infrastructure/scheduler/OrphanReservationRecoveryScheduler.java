package com.example.product.infrastructure.scheduler;

import com.example.product.application.service.OrphanReservationRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * orphan 예약 복구 스케줄러 (RST-03, D-P3-4). payment ProcessingRecoveryScheduler 동형.
 * Redisson 분산락으로 다중 product 인스턴스 중 단일 실행만 허용(이중 release 방지 — release 멱등과 이중 안전).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "product.orphan-recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrphanReservationRecoveryScheduler {

    private final RedissonClient redissonClient;
    private final OrphanReservationRecoveryService orphanReservationRecoveryService;

    @Value("${scheduler.lock.orphan-recovery}")
    private String lockKey;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[orphan-recovery] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[orphan-recovery] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            orphanReservationRecoveryService.recoverAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
