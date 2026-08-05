package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferStatus;
import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
import com.example.settlement.application.interfaces.OperationAlertPort;
import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.domain.entity.MerchantPayoutAccount;
import com.example.settlement.domain.entity.Payout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 지급 결과 수렴 chokepoint(CONFIRM-03) + poll backstop(CONFIRM-02).
 * webhook(PayoutCallbackController)·poll(PayoutPollScheduler)이 모두 applyResult 한 지점으로 funnel —
 * status-guarded UPDATE 가 승자 1건만 반영, 나머지는 0-row no-op.
 */
@Slf4j
@Service
public class PayoutResultService {

    private final PayoutRepository payoutRepo;
    private final BankTransferPort bankTransferPort;
    private final MerchantPayoutAccountRepository accountRepo;
    private final OperationAlertPort operationAlertPort;

    @Value("${payout.poll.grace-seconds:60}")
    private long graceSeconds;

    @Value("${payout.retry.max-attempts:5}")
    private int retryMax;

    @Value("${payout.retry.grace-seconds:60}")
    private long retryGraceSeconds;

    public PayoutResultService(PayoutRepository payoutRepo,
                               BankTransferPort bankTransferPort,
                               MerchantPayoutAccountRepository accountRepo,
                               OperationAlertPort operationAlertPort) {
        this.payoutRepo = payoutRepo;
        this.bankTransferPort = bankTransferPort;
        this.accountRepo = accountRepo;
        this.operationAlertPort = operationAlertPort;
    }

    /** status-guarded UPDATE 위임. 0-row = 이미 terminal/경합 → no-op(mirror reconcile 0-row alert). */
    public int applyResult(String transferRef, String result, String err) {
        int rows = payoutRepo.applyResult(transferRef, result, err);
        if (rows == 0) {
            log.info("[payout] applyResult no-op (raced/terminal) ref={} result={}", transferRef, result);
        }
        return rows;
    }

    /**
     * poll backstop(CONFIRM-02): grace 지난 PROCESSING 건을 은행 조회 → terminal 이면 SAME applyResult 로 수렴.
     * 한 건의 getStatus 실패가 나머지를 막지 않게 per-row try/catch(mirror reconcile runOnce).
     */
    public void pollStuckProcessing() {
        Instant cutoff = Instant.now().minusSeconds(graceSeconds);
        List<Payout> stuck = payoutRepo.findStuckProcessing(cutoff);
        log.info("[payout-poll] stuck PROCESSING={} cutoff={}", stuck.size(), cutoff);
        for (Payout p : stuck) {
            try {
                TransferStatus st = bankTransferPort.getStatus(p.getTransferRef());
                if (st == TransferStatus.PAID) {
                    applyResult(p.getTransferRef(), "PAID", null);
                } else if (st == TransferStatus.FAILED) {
                    applyResult(p.getTransferRef(), "FAILED", "bank reported FAILED");
                }
            } catch (Exception e) {
                log.error("[payout-poll] getStatus 실패 skip ref={}", p.getTransferRef(), e);
            }
        }
    }

    /**
     * 실패 재시도(RETRY-01): grace 지난 FAILED 건을 상한까지 재제출, 소진 시 terminal DEAD + alert-once.
     * 기존 poll tick(PayoutPollScheduler)에서 pollStuckProcessing 뒤에 같은 락 안에서 호출 — 새 스케줄러 빈 없음.
     * claimForRetry / markDeadIfExhausted 는 disjoint status-guarded UPDATE 라 rowcount==1 인 tick 만
     * 각각 재제출/알림 → 두 tick 동시라도 이중지급·알림폭풍 없음. per-row try/catch(한 건 실패가 나머지 안 막게).
     */
    public void retryFailed() {
        Instant cutoff = Instant.now().minusSeconds(retryGraceSeconds);
        List<Payout> failed = payoutRepo.findRetryableFailed(cutoff);
        log.info("[payout-retry] retryable FAILED={} cutoff={} max={}", failed.size(), cutoff, retryMax);
        for (Payout p : failed) {
            try {
                if (p.getAttemptCount() < retryMax) {
                    Optional<MerchantPayoutAccount> account = accountRepo.findActive(p.getMerchantId());
                    if (account.isEmpty()) {
                        // 계좌 비활성/미설정 → claim 안 함 → attempt 미소진 → 영영 DEAD 도 안 됨(계좌 복구 시 재개).
                        // ponytail: inert-FAILED — 계좌 복구 전까지 tick 마다 warn. 영구 비활성 계좌는 DEAD 도달 못함;
                        //           소음 심하면 재검토(DEAD+alert 로 승격 옵션은 지금은 보류).
                        log.warn("[payout-retry] active 계좌 없음 skip ref={} merchant={}",
                                p.getTransferRef(), p.getMerchantId());
                        continue;
                    }
                    int claimed = payoutRepo.claimForRetry(p.getTransferRef(), retryMax);
                    if (claimed == 1) {
                        bankTransferPort.submit(p.getTransferRef(), account.get(), p.getAmount());
                        log.info("[payout-retry] 재제출 ref={} attempt<{}", p.getTransferRef(), retryMax);
                    } else {
                        log.info("[payout-retry] claim no-op (raced) ref={}", p.getTransferRef());
                    }
                } else {
                    int dead = payoutRepo.markDeadIfExhausted(p.getTransferRef(), retryMax, "retry exhausted");
                    if (dead == 1) {
                        operationAlertPort.alert("[payout] retry exhausted → DEAD ref=" + p.getTransferRef()
                                + " attempt=" + p.getAttemptCount());
                        log.error("[payout-retry] DEAD ref={} attempt={}", p.getTransferRef(), p.getAttemptCount());
                    } else {
                        log.info("[payout-retry] dead no-op (raced/already DEAD) ref={}", p.getTransferRef());
                    }
                }
            } catch (Exception e) {
                log.error("[payout-retry] 재시도 실패 skip ref={}", p.getTransferRef(), e);
            }
        }
    }
}
