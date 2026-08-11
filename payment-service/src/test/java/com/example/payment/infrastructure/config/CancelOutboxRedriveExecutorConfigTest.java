package com.example.payment.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelOutboxRedriveExecutorConfigTest {

    private final CancelOutboxRedriveExecutorConfig config = new CancelOutboxRedriveExecutorConfig();

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveMaxConcurrencyBeforeCreatingExecutor(int maxConcurrency) {
        assertThatThrownBy(() -> config.cancelRedriveExecutor(maxConcurrency, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxConcurrency must be greater than 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveShutdownAwaitSecondsBeforeCreatingExecutor(int shutdownAwaitSeconds) {
        assertThatThrownBy(() -> config.cancelRedriveExecutor(5, shutdownAwaitSeconds))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("shutdownAwaitSeconds must be greater than 0");
    }
}
