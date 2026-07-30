package com.example.payment.infrastructure.scheduler;

import com.example.payment.application.service.StockReleaseRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * stock_release_retry 예약 해제 보상 재시도 스케줄러 (RSV-03, D-P2-6).
 * Redis 분산락으로 다중 인스턴스 중복 실행 방지 (30초 주기). CompensationRetryScheduler 동형.
 * 신규 스케줄러 — 취소 코어 scheduler 3종(pending/processing/compensation-recovery) 무변경(D-P2-7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReleaseRetryScheduler {

    private final RedissonClient redissonClient;
    private final StockReleaseRetryService stockReleaseRetryService;

    @Value("${scheduler.lock.stock-release-retry}")
    private String lockKey;

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 25, TimeUnit.SECONDS)) {
                log.debug("[stock-release-retry] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[stock-release-retry] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            stockReleaseRetryService.retryAll();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
