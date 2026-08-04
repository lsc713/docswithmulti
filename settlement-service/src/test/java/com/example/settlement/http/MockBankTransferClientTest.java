package com.example.settlement.http;

import com.example.settlement.application.interfaces.BankTransferPort.TransferAck;
import com.example.settlement.application.interfaces.BankTransferPort.TransferStatus;
import com.example.settlement.infrastructure.http.MockBankTransferClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** MOCK-01 단위 커버: submit accepted, getStatus 비-null(clone MockPgCancelClientTest). */
class MockBankTransferClientTest {

    MockBankTransferClient sut;

    @BeforeEach
    void setUp() {
        sut = new MockBankTransferClient();
    }

    @Test
    void submit_returns_accepted() {
        TransferAck ack = sut.submit("PO-1", null, BigDecimal.TEN);
        assertThat(ack).isNotNull();
        assertThat(ack.accepted()).isTrue();
    }

    @Test
    void get_status_returns_non_null() {
        TransferStatus status = sut.getStatus("PO-1");
        assertThat(status).isNotNull();
    }
}
