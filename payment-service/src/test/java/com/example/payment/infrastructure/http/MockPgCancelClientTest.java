package com.example.payment.infrastructure.http;

import com.example.payment.application.dto.PgCancelResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockPgCancelClientTest {

    MockPgCancelClient sut;

    @BeforeEach
    void setUp() {
        sut = new MockPgCancelClient();
    }

    @Test
    void cancel_returns_approved() {
        PgCancelResult result = sut.cancel("key", BigDecimal.TEN, "reason");

        assertThat(result).isNotNull();
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void get_status_returns_approved() {
        PgCancelResult result = sut.getStatus("key", BigDecimal.TEN);

        assertThat(result).isNotNull();
        assertThat(result.isApproved()).isTrue();
    }
}
