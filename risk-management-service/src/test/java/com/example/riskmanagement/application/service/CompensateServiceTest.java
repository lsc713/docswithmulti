package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.exception.CompensationMerchantMismatchException;
import com.example.riskmanagement.application.exception.DataInconsistencyException;
import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensateService")
class CompensateServiceTest {

    @Mock CancelUsageCompensationRepository compensationRepository;
    @Mock CancelUsageHistoryRepository historyRepository;
    @Mock MerchantCancelUsageRepository usageRepository;

    CompensateService sut;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);

    @BeforeEach
    void setUp() {
        sut = new CompensateService(compensationRepository, historyRepository, usageRepository);
    }

    @Test
    @DisplayName("이미 보상된 경우 ALREADY_COMPENSATED 반환")
    void execute_returns_already_compensated_when_duplicate() {
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(true);

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isFalse();
        assertThat(result.reason()).isEqualTo("ALREADY_COMPENSATED");
        verify(usageRepository, never()).tryRestore(anyLong(), any(), any());
    }

    @Test
    @DisplayName("차감 이력 없는 경우 NOT_CHARGED 반환")
    void execute_returns_not_charged_when_no_history() {
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.empty());

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isFalse();
        assertThat(result.reason()).isEqualTo("NOT_CHARGED");
        verify(usageRepository, never()).tryRestore(anyLong(), any(), any());
    }

    @Test
    @DisplayName("보상 성공 — 원자 tryRestore(1행) + compensation INSERT")
    void execute_restores_via_atomic_tryRestore() {
        CancelUsageHistory history = CancelUsageHistory.record("cr_001", 1L, TODAY, BigDecimal.valueOf(300_000));
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));
        when(usageRepository.tryRestore(1L, TODAY, BigDecimal.valueOf(300_000))).thenReturn(1);

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isTrue();
        assertThat(result.reason()).isNull();
        verify(usageRepository).tryRestore(1L, TODAY, BigDecimal.valueOf(300_000));
        verify(usageRepository, never()).findByMerchantIdAndKstDateForUpdate(anyLong(), any());
        verify(compensationRepository).save(any());
    }

    @Test
    @DisplayName("요청 merchantId ≠ 이력 merchantId → 400 BusinessException (호출자 잘못)")
    void execute_throws_mismatch_when_merchant_differs() {
        // 이력은 merchant 2 로 차감됐는데 요청은 merchant 1
        CancelUsageHistory history = CancelUsageHistory.record("cr_001", 2L, TODAY, BigDecimal.valueOf(300_000));
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));

        assertThatThrownBy(() -> sut.execute(
                new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000))))
            .isInstanceOf(CompensationMerchantMismatchException.class);

        verify(usageRepository, never()).tryRestore(anyLong(), any(), any());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("tryRestore 0행(대상 usage 없음=불변식 위반) → DataInconsistencyException")
    void execute_throws_data_inconsistency_when_no_row_restored() {
        CancelUsageHistory history = CancelUsageHistory.record("cr_001", 1L, TODAY, BigDecimal.valueOf(300_000));
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));
        when(usageRepository.tryRestore(1L, TODAY, BigDecimal.valueOf(300_000))).thenReturn(0);

        assertThatThrownBy(() -> sut.execute(
                new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000))))
            .isInstanceOf(DataInconsistencyException.class);

        verify(compensationRepository, never()).save(any());
    }
}
