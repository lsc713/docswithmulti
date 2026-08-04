package com.example.settlement.infrastructure.scheduler;

import com.example.settlement.application.service.PayoutResultService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 지급 poll backstop (CONFIRM-02, clone SettlementReconcileScheduler). 잃어버린 webhook 도 수렴시킨다.
 *
 * <p>60s fixedDelay + Redisson 분산락 단일 실행(reconcile 와 <b>별개</b> 락 키 lock:scheduler:payout-poll).
 * 락 획득 실패 시 즉시 skip. 순수 로직은 pollStuckProcessing 에서 — 테스트가 직접 호출.
 * RedissonClient/@EnableScheduling 은 이미 wired(reconcile) — 새 빈 추가 금지(RedissonAutoConfiguration 충돌).
 */
@Slf4j
@Component
public class PayoutPollScheduler {

    private final RedissonClient redissonClient;
    private final PayoutResultService payoutResultService;

    @Value("${scheduler.lock.payout-poll}")
    private String lockKey;

    public PayoutPollScheduler(RedissonClient redissonClient,
                               PayoutResultService payoutResultService) {
        this.redissonClient = redissonClient;
        this.payoutResultService = payoutResultService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[payout-poll] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[payout-poll] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            payoutResultService.pollStuckProcessing();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
