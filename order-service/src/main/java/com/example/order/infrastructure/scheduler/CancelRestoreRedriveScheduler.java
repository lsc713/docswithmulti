package com.example.order.infrastructure.scheduler;

import com.example.order.application.interfaces.CancelRestoreDlqRepository;
import com.example.order.application.interfaces.CancelRestoreDlqRepository.Redrivable;
import com.example.order.application.service.CancelRestoreRedriveService;
import com.example.order.application.usecase.ProcessCancelledItemsUseCase.Command;
import com.example.order.infrastructure.messaging.PaymentCancelledPayload;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * cancel_restore_dlq PENDING 자동 재구동 (Phase 1 product CancelRestoreRedriveScheduler 미러).
 *
 * <p>30s fixedDelay + Redisson 분산락 단일 실행. 파싱(인프라 관심사)은 스케줄러에서,
 * 상태전이(RESOLVED/bumpAttempt/DEAD)는 {@link CancelRestoreRedriveService} 에서.
 *
 * <p>백오프: updated_at &lt;= now-30s 인 PENDING 만 후보 → 캡처/시도가 updated_at 을 갱신하므로
 * 자연 30~60s 재시도 간격.
 */
@Slf4j
@Component
public class CancelRestoreRedriveScheduler {

    private static final int BATCH_LIMIT = 100;
    private static final long POLL_BACKOFF_SECONDS = 30;

    private final RedissonClient redissonClient;
    private final CancelRestoreDlqRepository dlqRepository;
    private final CancelRestoreRedriveService redriveService;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.lock.cancel-restore-redrive}")
    private String lockKey;

    public CancelRestoreRedriveScheduler(RedissonClient redissonClient,
                                         CancelRestoreDlqRepository dlqRepository,
                                         CancelRestoreRedriveService redriveService,
                                         ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.dlqRepository = dlqRepository;
        this.redriveService = redriveService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 30_000)
    public void run() {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 55, TimeUnit.SECONDS)) {
                log.debug("[cancel-restore-redrive] 락 획득 실패 — skip");
                return;
            }
        } catch (InterruptedException e) {
            log.debug("[cancel-restore-redrive] 락 획득 중단");
            Thread.currentThread().interrupt();
            return;
        }
        try {
            pollOnce(Instant.now().minusSeconds(POLL_BACKOFF_SECONDS));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * PENDING(updated_at &lt;= redrivableBefore) 을 재구동. 락 밖 순수 로직 — 테스트가 직접 호출.
     */
    public void pollOnce(Instant redrivableBefore) {
        List<Redrivable> rows = dlqRepository.findRedrivable(redrivableBefore, BATCH_LIMIT);
        for (Redrivable r : rows) {
            try {
                PaymentCancelledPayload payload =
                    objectMapper.readValue(r.payload(), PaymentCancelledPayload.class);
                // order payload 는 orderItemId 만 가짐(skuId/quantity 없음) → List<Long> 매핑.
                List<Long> orderItemIds = payload.cancelledItems().stream()
                    .map(PaymentCancelledPayload.CancelledItem::orderItemId)
                    .toList();
                Command command = new Command(payload.cancelRequestId(), orderItemIds);
                redriveService.redriveOne(r.cancelRequestId(), r.attemptCount(), command);
            } catch (Exception e) {
                // 파싱 실패 등은 이 행만 skip(다른 행 재구동 계속) — 다음 폴에서 재시도.
                log.error("[cancel-restore-redrive] 행 재구동 실패 skip cancelRequestId={}",
                    r.cancelRequestId(), e);
            }
        }
    }
}
