package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.TossPaymentPort;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("mock-pg & !prod")
public class MockTossPaymentClient implements TossPaymentPort {

    @Override
    public void confirm(String paymentKey, String paymentRequestId, BigDecimal amount) {
        log.info("Mock PG 결제 승인. paymentKey={}, paymentRequestId={}, amount={}",
            paymentKey, paymentRequestId, amount);
    }

    @Override
    public Status getStatus(String paymentKey) {
        return Status.DONE;
    }
}
