package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import com.example.payment.application.interfaces.PgCancelPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Component
@Profile("local")
public class MockPgCancelClient implements PgCancelPort {

    @Override
    public PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason) {
        String mockTxId = "mock-" + UUID.randomUUID();
        log.info("[MockPgCancelClient] PG 취소 성공 처리. paymentKey={}, cancelAmount={}, txId={}",
            paymentKey, cancelAmount, mockTxId);
        return PgCancelResult.approved(mockTxId);
    }

    @Override
    public PgCancelResult getStatus(String paymentKey, BigDecimal cancelAmount) {
        String mockTxId = "mock-status-" + UUID.randomUUID();
        log.info("[MockPgCancelClient] PG 취소 상태 조회. paymentKey={}, cancelAmount={}, txId={}",
            paymentKey, cancelAmount, mockTxId);
        return PgCancelResult.approved(mockTxId);
    }
}
