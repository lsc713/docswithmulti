package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.OperationAlertPort;
import com.example.settlement.application.interfaces.PaymentSettlementPort;
import com.example.settlement.application.interfaces.PaymentSettlementPort.CancelView;
import com.example.settlement.application.interfaces.PaymentSettlementPort.PaymentView;
import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.application.usecase.RecordCancellationUseCase;
import com.example.settlement.application.usecase.RecordSaleUseCase;
import com.example.settlement.domain.entity.Settlement;
import com.example.settlement.domain.service.SettlementFeeCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * reconcile → finalize (RECON-01/02). 주마감 유예 지난 OPEN 헤더를 payment 조회로 back-fill 하고
 * Σlines 권위로 fee/vat/net 을 계산해 OPEN→FINALIZED 로 확정한다.
 *
 * <p>순수 조합(락 밖) — 스케줄러가 Redisson 락 안에서 runOnce 를 호출한다. back-fill 은 <b>기존</b>
 * SaleLedgerService/CancelLedgerService.record() 경로를 재사용(event_id UK dedup + 원자 증분 공유)하므로
 * header==Σlines 가 구성적으로 성립 — Σlines 재계산은 aggregator 가 아니라 <i>drift 탐지기</i>(D-Q3 alert-only).
 */
@Slf4j
@Service
public class SettlementReconcileService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final SettlementRepository settlementRepo;
    private final SettlementLineRepository lineRepo;
    private final PaymentSettlementPort paymentSettlementPort;
    private final RecordSaleUseCase saleLedger;
    private final RecordCancellationUseCase cancelLedger;
    private final SettlementConfigService configService;
    private final OperationAlertPort alertPort;

    public SettlementReconcileService(SettlementRepository settlementRepo,
                                      SettlementLineRepository lineRepo,
                                      PaymentSettlementPort paymentSettlementPort,
                                      RecordSaleUseCase saleLedger,
                                      RecordCancellationUseCase cancelLedger,
                                      SettlementConfigService configService,
                                      OperationAlertPort alertPort) {
        this.settlementRepo = settlementRepo;
        this.lineRepo = lineRepo;
        this.paymentSettlementPort = paymentSettlementPort;
        this.saleLedger = saleLedger;
        this.cancelLedger = cancelLedger;
        this.configService = configService;
        this.alertPort = alertPort;
    }

    /** cutoff = today(KST) − grace-days. period_end < cutoff 인 OPEN 헤더를 순회 finalize. */
    public void runOnce(LocalDate cutoff) {
        List<Settlement> targets = settlementRepo.findFinalizable(cutoff);
        log.info("[reconcile] finalizable headers={} cutoff={}", targets.size(), cutoff);
        for (Settlement h : targets) {
            try {
                reconcileAndFinalize(h);
            } catch (Exception e) {
                // 한 헤더의 조회/처리 실패가 나머지를 막지 않게 skip — 다음 폴에서 재시도(idempotent).
                log.error("[reconcile] 헤더 처리 실패 skip id={}", h.getId(), e);
            }
        }
    }

    void reconcileAndFinalize(Settlement h) {
        Instant from = h.getPeriodStart().atStartOfDay(KST).toInstant();
        Instant to = h.getPeriodEnd().plusDays(1).atStartOfDay(KST).toInstant(); // exclusive

        // (2) HTTP pull — 실패 시 예외 전파(빈 리스트 강등 금지).
        List<PaymentView> data = paymentSettlementPort.fetch(h.getMerchantId(), from, to);

        // (3) back-fill: 기존 record() 경로 재사용(event_id UK dedup). SALE 은 창 안 createdAt 만.
        for (PaymentView p : data) {
            if (!p.createdAt().isBefore(from) && p.createdAt().isBefore(to)) {
                backfill(() -> saleLedger.record(new RecordSaleUseCase.Command(
                    p.paymentKey(), h.getMerchantId(), p.totalAmount(), p.createdAt())));
            }
            for (CancelView c : p.cancels()) {
                backfill(() -> cancelLedger.record(new RecordCancellationUseCase.Command(
                    c.cancelRequestId(), p.paymentKey(), h.getMerchantId(), c.cancelAmount(), c.completedAt())));
            }
        }

        // (4) Σlines 권위 재계산 + drift alert-only.
        Map<String, BigDecimal> sums = lineRepo.sumLinesByType(h.getId());
        BigDecimal gross = sums.getOrDefault("SALE", ZERO);
        BigDecimal cancel = sums.getOrDefault("CANCEL", ZERO);
        Settlement fresh = settlementRepo.findById(h.getId()).orElse(h);
        if (gross.compareTo(fresh.getGrossAmount()) != 0 || cancel.compareTo(fresh.getCancelAmount()) != 0) {
            alertPort.alert("settlement drift id=" + h.getId()
                + " grossΣ=" + gross + " header=" + fresh.getGrossAmount()
                + " cancelΣ=" + cancel + " header=" + fresh.getCancelAmount());
            // 헤더 gross/cancel 은 재작성하지 않음(drift alert-only) — finalize 는 Σlines 권위로 계산.
        }

        // (5) rate 미설정 → finalize 유예(OPEN 유지 + alert).
        Optional<BigDecimal> rate = configService.getRate(h.getMerchantId());
        if (rate.isEmpty()) {
            alertPort.alert("rate unset, defer finalize merchant=" + h.getMerchantId() + " id=" + h.getId());
            return;
        }

        // (6) Σlines 권위로 fee/vat/net 계산.
        SettlementFeeCalculator.Result r = SettlementFeeCalculator.compute(gross, cancel, rate.get());

        // (7) status-guarded 원자 finalize — 0 rows = 이미 FINALIZED/경합 → alert, blind retry 없음.
        int rows = settlementRepo.finalizeOpen(h.getId(), r.fee(), r.vat(), r.net());
        if (rows == 0) {
            alertPort.alert("finalize raced/already-finalized id=" + h.getId());
        } else {
            log.info("[reconcile] finalized id={} gross={} cancel={} fee={} vat={} net={}",
                h.getId(), gross, cancel, r.fee(), r.vat(), r.net());
        }
    }

    /** record() 는 processed_settlement_event 선체크로 재실행 no-op — 경쟁 재전달의 UK 충돌만 belt-and-suspenders 로 흡수. */
    private void backfill(Runnable record) {
        try {
            record.run();
        } catch (DataIntegrityViolationException dup) {
            // event_id UK 충돌 = 이미 적재됨 → no-op.
        }
    }
}
