package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.CancelUsageHistoryRepository;
import com.example.riskmanagement.application.usecase.CheckChargeUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckChargeService")
class CheckChargeServiceTest {

    @Mock CancelUsageHistoryRepository historyRepository;
    @InjectMocks CheckChargeService sut;

    @Test
    @DisplayName("차감된 경우 charged=true 반환")
    void execute_returns_charged_true_when_history_exists() {
        CancelUsageHistory history = CancelUsageHistory.record(
            "cr_001", 1L, LocalDate.of(2026, 4, 23), BigDecimal.valueOf(300_000));
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));

        CheckChargeUseCase.Result result = sut.execute("cr_001");

        assertThat(result.charged()).isTrue();
        assertThat(result.cancelAmount()).isEqualByComparingTo(BigDecimal.valueOf(300_000));
    }

    @Test
    @DisplayName("차감 없는 경우 charged=false 반환")
    void execute_returns_charged_false_when_no_history() {
        when(historyRepository.findByCancelRequestId("cr_999")).thenReturn(Optional.empty());

        CheckChargeUseCase.Result result = sut.execute("cr_999");

        assertThat(result.charged()).isFalse();
        assertThat(result.merchantId()).isNull();
    }
}
