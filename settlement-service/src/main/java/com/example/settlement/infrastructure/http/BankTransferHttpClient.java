package com.example.settlement.infrastructure.http;

import com.example.settlement.application.interfaces.BankTransferPort;
import com.example.settlement.domain.entity.MerchantPayoutAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 은행 송금 실 어댑터 stub(@Profile("!local")) — Phase 1 은 실 은행 미연동(호출 계약만 확정).
 * @Value 에 default 를 둬 profile 미지정 IT 에서도 빈 인스턴스화 성공(RESEARCH Pitfall 2) — IT 는 @MockitoBean 으로 대체.
 * 실 송금/조회 연동은 후속 Phase. Phase 1 에서는 accepted/PROCESSING 을 반환하는 안전한 기본값.
 */
@Slf4j
@Component
@Profile("!local")
public class BankTransferHttpClient implements BankTransferPort {

    private final String bankUrl;

    public BankTransferHttpClient(@Value("${payout.bank.url:http://localhost:9099}") String bankUrl) {
        this.bankUrl = bankUrl;
    }

    @Override
    public TransferAck submit(String transferRef, MerchantPayoutAccount account, BigDecimal amount) {
        log.info("[BankTransferHttpClient] (stub) 송금 제출 url={} transferRef={} amount={}",
            bankUrl, transferRef, amount);
        return new TransferAck(true);
    }

    @Override
    public TransferStatus getStatus(String transferRef) {
        log.info("[BankTransferHttpClient] (stub) 송금 상태 조회 url={} transferRef={}", bankUrl, transferRef);
        return TransferStatus.PROCESSING;
    }
}
