package com.example.settlement.application.interfaces;

import com.example.settlement.domain.entity.MerchantPayoutAccount;

import java.math.BigDecimal;

/**
 * 은행 송금 외부 포트(payment PgCancelPort 미러). submit=송금 제출(비동기, accepted 반환),
 * getStatus=송금 상태 조회(poll backstop 용). 실 구현은 @Profile 로 mock/http 분리, IT 는 @MockitoBean 으로 대체.
 */
public interface BankTransferPort {

    /** 송금 제출 — 접수(accepted) 반환. 실제 PAID 확정은 webhook/poll 로 비동기 수렴. */
    TransferAck submit(String transferRef, MerchantPayoutAccount account, BigDecimal amount);

    /** 송금 상태 조회 — poll backstop 이 stuck PROCESSING 을 확인할 때 호출. */
    TransferStatus getStatus(String transferRef);

    record TransferAck(boolean accepted) {}

    enum TransferStatus { PROCESSING, PAID, FAILED }
}
