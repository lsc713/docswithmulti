package com.example.settlement.infrastructure.http;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.domain.entity.MerchantPayoutAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 은행 송금 mock(@Profile("local")) — 로컬 docker compose 실행용. submit=accepted, getStatus=PAID(결정적).
 * IT 는 이 빈 대신 @MockitoBean BankTransferPort 로 PAID/FAILED 시나리오를 제어(reconcile IT 관례).
 */
@Slf4j
@Component
@Profile("local")
public class MockBankTransferClient implements BankTransferPort {

    @Override
    public TransferAck submit(String transferRef, MerchantPayoutAccount account, BigDecimal amount) {
        log.info("[MockBankTransferClient] 송금 제출 accepted. transferRef={}, amount={}", transferRef, amount);
        return new TransferAck(true);
    }

    @Override
    public TransferStatus getStatus(String transferRef) {
        log.info("[MockBankTransferClient] 송금 상태 조회 → PAID. transferRef={}", transferRef);
        return TransferStatus.PAID;
    }
}
