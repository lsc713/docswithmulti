package com.example.settlement.application.service;

import com.example.settlement.application.exception.InvalidPayoutAccountException;
import com.example.settlement.application.exception.MerchantPayoutAccountNotFoundException;
import com.example.settlement.application.exception.PayoutAccountInactiveException;
import com.example.settlement.application.exception.PayoutAlreadyExistsException;
import com.example.settlement.application.exception.PayoutNotFoundException;
import com.example.settlement.application.exception.PayoutNotPayableException;
import com.example.settlement.application.exception.SettlementNotFoundException;
import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.application.interfaces.MerchantPayoutAccountRepository;
import com.example.settlement.application.interfaces.MerchantReserveConfigRepository;
import com.example.settlement.application.interfaces.PayoutRepository;
import com.example.settlement.application.interfaces.ReserveRepository;
import com.example.settlement.application.interfaces.SettlementRepository;
import com.example.settlement.domain.entity.MerchantPayoutAccount;
import com.example.settlement.domain.entity.MerchantReserveConfig;
import com.example.settlement.domain.entity.Payout;
import com.example.settlement.domain.entity.Settlement;
import com.example.settlement.domain.service.ReserveCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 지급 계좌 설정(ACCT-01) + 지급 승인(PAY-01).
 *
 * <p>approve: FINALIZED 정산 헤더의 <b>자기 DB 행</b>에서 net_amount 를 읽어(결제 HTTP 없음, INV-01) 스냅샷으로 지급.
 * 가드(FINALIZED ∧ active 계좌 ∧ net>0 ∧ 기존 지급 없음) 통과 시 transfer_ref='PO-'+settlementId 로
 * PROCESSING 단일 INSERT → <b>save 후</b> BankTransferPort.submit. 검증 실패는 BusinessException(→400,
 * PAYOUT_NOT_PAYABLE / PAYOUT_ACCOUNT_INACTIVE) 또는 SETTLEMENT_NOT_FOUND(404, GlobalExceptionHandler).
 * 비-@Transactional: save 는 자체 TX 로 커밋되고, transfer_ref durable 확보 후 network submit(2차 write 없음).
 */
@Slf4j
@Service
public class PayoutService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SettlementRepository settlementRepo;
    private final PayoutRepository payoutRepo;
    private final MerchantPayoutAccountRepository accountRepo;
    private final BankTransferPort bankTransferPort;
    private final MerchantReserveConfigRepository reserveConfigRepo;
    private final ReserveRepository reserveRepo;

    public PayoutService(SettlementRepository settlementRepo,
                         PayoutRepository payoutRepo,
                         MerchantPayoutAccountRepository accountRepo,
                         BankTransferPort bankTransferPort,
                         MerchantReserveConfigRepository reserveConfigRepo,
                         ReserveRepository reserveRepo) {
        this.settlementRepo = settlementRepo;
        this.payoutRepo = payoutRepo;
        this.accountRepo = accountRepo;
        this.bankTransferPort = bankTransferPort;
        this.reserveConfigRepo = reserveConfigRepo;
        this.reserveRepo = reserveRepo;
    }

    /** 계좌 설정(멱등 upsert). 빈 값은 400(mirror SettlementConfigService.setRate — @Transactional 로 @Modifying 실행 컨텍스트 확보). */
    @Transactional
    public void upsertAccount(long merchantId, String bankCode, String accountNumber, String holderName) {
        requireNonBlank(bankCode, "bankCode");
        requireNonBlank(accountNumber, "accountNumber");
        requireNonBlank(holderName, "holderName");
        accountRepo.upsert(merchantId, bankCode, accountNumber, holderName);
    }

    /** 활성 지급 계좌 조회(ACCT-02). 없으면 404. */
    public MerchantPayoutAccount getAccount(long merchantId) {
        return accountRepo.findActive(merchantId)
            .orElseThrow(() -> new MerchantPayoutAccountNotFoundException(merchantId));
    }

    /** 정산 헤더의 지급 건 조회(PAY-03). 없으면 404. */
    public Payout getPayout(long settlementId) {
        return payoutRepo.findBySettlementId(settlementId)
            .orElseThrow(() -> new PayoutNotFoundException(settlementId));
    }

    /** FINALIZED 정산 → PROCESSING 지급 승인 + 은행 제출. */
    public Payout approve(long settlementId) {
        Settlement s = settlementRepo.findById(settlementId)
            .orElseThrow(() -> new SettlementNotFoundException(settlementId));
        if (!"FINALIZED".equals(s.getStatus())) {
            throw new PayoutNotPayableException("FINALIZED 정산만 지급 승인 가능합니다. status=" + s.getStatus());
        }
        BigDecimal net = s.getNetAmount();
        if (net == null || net.signum() <= 0) {
            throw new PayoutNotPayableException("net_amount가 0 이하입니다: " + net);
        }
        MerchantPayoutAccount account = accountRepo.findActive(s.getMerchantId())
            .orElseThrow(() -> new PayoutAccountInactiveException(s.getMerchantId()));
        // pre-check: 순차 재승인 → 409-return-existing(이중지급 차단)
        payoutRepo.findBySettlementId(settlementId).ifPresent(existing -> {
            throw new PayoutAlreadyExistsException(existing);
        });

        // 유보 산정(읽기 전용): 정책 없음/비활성/rate 0/cap 소진 → reserve=0 → payout=net(하위호환).
        Optional<MerchantReserveConfig> configOpt = reserveConfigRepo.findConfig(s.getMerchantId());
        BigDecimal held = reserveRepo.currentHeld(s.getMerchantId());
        BigDecimal reserve = ReserveCalculator.compute(net, configOpt, held);
        BigDecimal payoutAmount = net.subtract(reserve);
        if (payoutAmount.signum() <= 0) {
            // 방어 가드: rate<1 ∧ reserve≤net 이라 정상경로에선 도달 불가(cap/round 로도 reserve<net). 방어용.
            throw new PayoutNotPayableException("유보 차감 후 지급액이 0 이하입니다: net=" + net + " reserve=" + reserve);
        }

        String transferRef = "PO-" + settlementId;
        Payout payout;
        try {
            payout = payoutRepo.insertProcessing(settlementId, s.getMerchantId(), payoutAmount, transferRef);
        } catch (DataIntegrityViolationException dup) {
            // ponytail: approve() 는 의도적으로 비-@Transactional. @GeneratedValue(IDENTITY) 는 save() 시점에
            // INSERT 를 flush 하므로 uk_payout_settlement 위반이 여기(catch)에서 DIVE 로 표면화된다 → 경합 패자를
            // 409-return-existing 으로 전환. @Transactional 래퍼를 두면 INSERT 가 commit 시점으로 이동해 catch 를
            // 벗어나고 패자가 500 으로 회귀 + submit 이 이미 발사됨(이중지급). 승자 재조회 후 409, 없으면 원 예외 재던짐.
            // 유보 INSERT 는 payout INSERT 성공 후에만 도달하므로 패자(여기 DIVE)는 reserve 행을 만들지 않는다.
            Payout won = payoutRepo.findBySettlementId(settlementId)
                .orElseThrow(() -> dup);
            throw new PayoutAlreadyExistsException(won);
        }
        // reserve HELD durable write — payout INSERT 성공 후, submit 이전(별도 @Transactional).
        // ponytail: reserve INSERT 를 payout INSERT 와 한 TX 로 묶지 말 것 — 묶으면 payout INSERT 가 commit 시점으로
        // 밀려 DIVE 가 catch 를 벗어나 409→500 회귀(이중지급). 2·3 사이 크래시 창은 리컨실 탐지 수용 엣지(범위 밖).
        // ponytail: cap 은 approve 단위 best-effort — 동일 가맹점 동시 승인은 reserve_cap 을 일시 초과할 수 있다;
        //           merchant 락은 실제로 문제될 때만 추가.
        if (reserve.signum() > 0) {
            LocalDate holdUntil = LocalDate.now(KST).plusDays(configOpt.get().getHoldDays());
            reserveRepo.insertHeld(settlementId, s.getMerchantId(), reserve, holdUntil, "RSV-" + settlementId);
        }
        bankTransferPort.submit(transferRef, account, payoutAmount);   // INSERT 성공 후 제출 — 패자는 여기 도달 못함
        log.info("[payout] 승인 settlement={} transferRef={} net={} reserve={} payout={}",
            settlementId, transferRef, net, reserve, payoutAmount);
        return payout;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPayoutAccountException(field);
        }
    }
}
