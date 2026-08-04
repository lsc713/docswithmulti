package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.OperationAlertPort;
import com.example.settlement.application.interfaces.ProcessedSettlementEventRepository;
import com.example.settlement.application.interfaces.SettlementLineRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.application.usecase.RecordSaleUseCase;
import com.example.settlement.domain.service.SettlementWeek;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * 완료 매출 이벤트 → SALE 원장 라인 적재 + 헤더 upsert/gross 증분 (SALE-01/02, CancelLedgerService 동형).
 *
 * <p><b>멱등 이중 가드</b>: processed_settlement_event 선체크(최적화) → settlement_line.event_id UK(권위 가드).
 * 라인 insert 가 gross 증분보다 <i>먼저</i> 같은 TX에서 실행되므로 UK 충돌 시 증분까지 통째로 롤백(부분 증분 누수 없음).
 */
@Service
public class SaleLedgerService implements RecordSaleUseCase {

    private final SettlementRepository settlementRepo;
    private final SettlementLineRepository lineRepo;
    private final ProcessedSettlementEventRepository processedRepo;
    private final OperationAlertPort operationAlertPort;
    private final TransactionTemplate transactionTemplate;

    public SaleLedgerService(SettlementRepository settlementRepo,
                             SettlementLineRepository lineRepo,
                             ProcessedSettlementEventRepository processedRepo,
                             OperationAlertPort operationAlertPort,
                             TransactionTemplate transactionTemplate) {
        this.settlementRepo = settlementRepo;
        this.lineRepo = lineRepo;
        this.processedRepo = processedRepo;
        this.operationAlertPort = operationAlertPort;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void record(Command cmd) {
        transactionTemplate.execute(status -> {
            String eventId = "sale:" + cmd.paymentKey();
            if (processedRepo.existsByEventId(eventId)) {
                return null; // 중복 이벤트 → no-op (추가 적재/증분 없음)
            }
            LocalDate periodStart = SettlementWeek.mondayOf(cmd.occurredAt());
            LocalDate periodEnd = SettlementWeek.sundayOf(cmd.occurredAt());

            settlementRepo.ensureRow(cmd.merchantId(), periodStart, periodEnd);
            long settlementId = settlementRepo.findId(cmd.merchantId(), periodStart);

            // FINALIZED 불변 가드(공유 chokepoint — 라이브 컨슈머·리컨실 모두 여기 경유). 늦은 이벤트는
            // 라인/증분/마커 없이 alert 만(삼키는 0-row UPDATE 금지 — RESEARCH Pitfall 4). ensureRow 는 status 를
            // OPEN 으로 리셋하지 않으므로(ON DUPLICATE = merchant_id=merchant_id) 그 뒤 status 읽기는 안전.
            if (isFinalized(cmd.merchantId(), periodStart)) {
                operationAlertPort.alert("late event on FINALIZED settlement merchant=" + cmd.merchantId()
                    + " period=" + periodStart + " eventId=" + eventId);
                return null;
            }

            // 라인 insert 가 증분보다 먼저 — UK 충돌 시 증분까지 같은 TX에서 롤백.
            lineRepo.insert(settlementId, "SALE", cmd.paymentKey(),
                cmd.grossAmount(), eventId, cmd.occurredAt());
            settlementRepo.addGrossAmount(cmd.merchantId(), periodStart, cmd.grossAmount());

            processedRepo.save(eventId);
            return null;
        });
    }

    private boolean isFinalized(long merchantId, LocalDate periodStart) {
        return settlementRepo.findStatus(merchantId, periodStart)
            .filter("FINALIZED"::equals)
            .isPresent();
    }
}
