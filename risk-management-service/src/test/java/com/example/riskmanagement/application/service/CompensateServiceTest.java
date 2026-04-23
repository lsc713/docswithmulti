package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.CompensateUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
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
        sut = new CompensateService(compensationRepository, historyRepository, usageRepository,
            new CancelLimitDomainService());
    }

    @Test
    @DisplayName("이미 보상된 경우 ALREADY_COMPENSATED 반환")
    void execute_returns_already_compensated_when_duplicate() {
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(true);

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isFalse();
        assertThat(result.reason()).isEqualTo("ALREADY_COMPENSATED");
        verify(usageRepository, never()).save(any());
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
    }

    @Test
    @DisplayName("보상 성공 — used_amount 복원 + compensation INSERT")
    void execute_restores_and_inserts_compensation() {
        CancelUsageHistory history = CancelUsageHistory.record("cr_001", 1L, TODAY, BigDecimal.valueOf(300_000));
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(300_000));
        when(compensationRepository.existsByCancelRequestId("cr_001")).thenReturn(false);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(history));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage));
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensateUseCase.Result result = sut.execute(
            new CompensateUseCase.Command("cr_001", 1L, BigDecimal.valueOf(300_000)));

        assertThat(result.restored()).isTrue();
        assertThat(result.reason()).isNull();
        verify(compensationRepository).save(any());
    }
}
