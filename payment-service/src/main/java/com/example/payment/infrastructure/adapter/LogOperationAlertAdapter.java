package com.example.payment.infrastructure.adapter;

import com.example.payment.application.interfaces.OperationAlertPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Slf4j
@Component
public class LogOperationAlertAdapter implements OperationAlertPort {

    @Override
    public void alertPgPendingTimeout(long cancelRequestId, String paymentKey, Instant pgPendingSince) {
        log.error("[ALERT] PG pending 1시간 초과 — 수동 확인 필요. cancelRequestId={} paymentKey={} pgPendingSince={}",
            cancelRequestId, paymentKey, pgPendingSince);
    }
}
