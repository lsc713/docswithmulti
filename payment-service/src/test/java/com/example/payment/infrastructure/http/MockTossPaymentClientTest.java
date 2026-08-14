package com.example.payment.infrastructure.http;

import com.example.payment.application.interfaces.TossPaymentPort;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockTossPaymentClientTest {

    private final MockTossPaymentClient sut = new MockTossPaymentClient();

    @Test
    void confirm_succeeds_without_external_pg() {
        sut.confirm("mock-payment-key", "request-id", BigDecimal.valueOf(29000));
    }

    @Test
    void recovery_status_is_done() {
        assertThat(sut.getStatus("mock-payment-key")).isEqualTo(TossPaymentPort.Status.DONE);
    }
}
