package com.example.settlement.infrastructure.scheduler;

import com.example.settlement.application.service.SettlementReconcileService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * reconcile → finalize 주기 실행 (RECON-01, product CancelRestoreRedriveScheduler 미러).
 *
 * <p>1h fixedDelay + Redisson 분산락 단일 실행. cutoff = today(KST) − grace-days.
 * 락 획득 실패 시 즉시 skip(중복 실행 방지). 순수 로직은 runOnce 에서 — 테스트가 직접 호출.
 */
@Slf4j
@Component
public class SettlementReconcileScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RedissonClient redissonClient;
    private final SettlementReconcileService reconcileService;

    @Value("${scheduler.lock.settlement-reconcile}")
    private String lockKey;

    @Value("${settlement.reconcile.grace-days:1}")
    private int graceDays;

    public SettlementReconcileScheduler(RedissonClient redissonClient,
                                        SettlementReconcileService reconcileService) {
        this.redissonClient = redissonClient;
        this.reconcileService = reconcileService;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[settlement-reconcile] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[settlement-reconcile] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            reconcileService.runOnce(LocalDate.now(KST).minusDays(graceDays));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
