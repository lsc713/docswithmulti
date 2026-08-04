package com.example.settlement.application.service;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.domain.entity.MerchantPayoutAccount;
import com.example.settlement.domain.entity.Payout;
import com.example.settlement.domain.entity.Settlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 지급 계좌 설정(ACCT-01) + 지급 승인(PAY-01).
 *
 * <p>approve: FINALIZED 정산 헤더의 <b>자기 DB 행</b>에서 net_amount 를 읽어(결제 HTTP 없음, INV-01) 스냅샷으로 지급.
 * 가드(FINALIZED ∧ active 계좌 ∧ net>0 ∧ 기존 지급 없음) 통과 시 transfer_ref='PO-'+settlementId 로
 * PROCESSING 단일 INSERT → <b>save 후</b> BankTransferPort.submit. 검증 실패는 IllegalArgumentException(→400).
 * 비-@Transactional: save 는 자체 TX 로 커밋되고, transfer_ref durable 확보 후 network submit(2차 write 없음).
 */
@Slf4j
@Service
public class PayoutService {

    private final SettlementRepository settlementRepo;
    private final PayoutRepository payoutRepo;
    private final MerchantPayoutAccountRepository accountRepo;
    private final BankTransferPort bankTransferPort;

    public PayoutService(SettlementRepository settlementRepo,
                         PayoutRepository payoutRepo,
                         MerchantPayoutAccountRepository accountRepo,
                         BankTransferPort bankTransferPort) {
        this.settlementRepo = settlementRepo;
        this.payoutRepo = payoutRepo;
        this.accountRepo = accountRepo;
        this.bankTransferPort = bankTransferPort;
    }

    /** 계좌 설정(멱등 upsert). 빈 값은 400(mirror SettlementConfigService.setRate — @Transactional 로 @Modifying 실행 컨텍스트 확보). */
    @Transactional
    public void upsertAccount(long merchantId, String bankCode, String accountNumber, String holderName) {
        requireNonBlank(bankCode, "bankCode");
        requireNonBlank(accountNumber, "accountNumber");
        requireNonBlank(holderName, "holderName");
        accountRepo.upsert(merchantId, bankCode, accountNumber, holderName);
    }

    /** FINALIZED 정산 → PROCESSING 지급 승인 + 은행 제출. */
    public Payout approve(long settlementId) {
        Settlement s = settlementRepo.findById(settlementId)
            .orElseThrow(() -> new IllegalArgumentException("정산 헤더가 없습니다: " + settlementId));
        if (!"FINALIZED".equals(s.getStatus())) {
            throw new IllegalArgumentException("FINALIZED 정산만 지급 승인 가능합니다: status=" + s.getStatus());
        }
        BigDecimal net = s.getNetAmount();
        if (net == null || net.signum() <= 0) {
            throw new IllegalArgumentException("net_amount가 0 이하입니다: " + net);
        }
        MerchantPayoutAccount account = accountRepo.findActive(s.getMerchantId())
            .orElseThrow(() -> new IllegalArgumentException(
                "활성 지급 계좌가 없습니다: merchant=" + s.getMerchantId()));
        if (payoutRepo.findBySettlementId(settlementId).isPresent()) {
            throw new IllegalArgumentException("이미 지급 건이 존재합니다: settlement=" + settlementId);
        }

        String transferRef = "PO-" + settlementId;
        Payout payout = payoutRepo.insertProcessing(settlementId, s.getMerchantId(), net, transferRef);
        bankTransferPort.submit(transferRef, account, net);   // save 후 제출(strictly after)
        log.info("[payout] 승인 settlement={} transferRef={} amount={}", settlementId, transferRef, net);
        return payout;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
    }
}
