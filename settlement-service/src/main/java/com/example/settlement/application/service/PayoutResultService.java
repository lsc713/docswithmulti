package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.BankTransferPort.TransferStatus;
import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.domain.entity.Payout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

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

    @Value("${payout.poll.grace-seconds:60}")
    private long graceSeconds;

    public PayoutResultService(PayoutRepository payoutRepo, BankTransferPort bankTransferPort) {
        this.payoutRepo = payoutRepo;
        this.bankTransferPort = bankTransferPort;
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
}
