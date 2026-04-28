package com.example.payment.application.service;

import com.example.payment.application.interfaces.CompensationRetryRepository;
import com.example.payment.application.interfaces.CompensationRetryRepository.PendingCompensation;
import com.example.payment.application.interfaces.RiskManagementPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompensationRetryService")
class CompensationRetryServiceTest {

    @Mock CompensationRetryRepository compensationRetryRepository;
    @Mock RiskManagementPort riskManagementPort;

    private CompensationRetryService service;

    @BeforeEach
    void setUp() {
        service = new CompensationRetryService(compensationRetryRepository, riskManagementPort);
    }

    @Test
    @DisplayName("대상 건이 없으면 아무 작업도 하지 않는다")
    void shouldDoNothingWhenNoPendingCompensations() {
        when(compensationRetryRepository.findDueForRetry(any())).thenReturn(List.of());

        service.retryAll();

        verify(riskManagementPort, never()).compensate(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("보상 성공 시 markDone을 호출한다")
    void shouldMarkDoneWhenCompensateSucceeds() {
        PendingCompensation pending = new PendingCompensation(
            1L, 10L, 5L, BigDecimal.valueOf(30_000), 0);
        when(compensationRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));

        service.retryAll();

        verify(riskManagementPort).compensate(10L, 5L, BigDecimal.valueOf(30_000));
        verify(compensationRetryRepository).markDone(1L);
        verify(compensationRetryRepository, never()).markRetryLater(anyLong(), anyInt(), any(), any());
        verify(compensationRetryRepository, never()).exhaust(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("보상 실패 + 최대 시도 미만 → markRetryLater 호출")
    void shouldMarkRetryLaterWhenFailsAndBelowMaxAttempts() {
        PendingCompensation pending = new PendingCompensation(
            2L, 20L, 5L, BigDecimal.valueOf(50_000), 2); // attempt=2, nextAttempt=3 < 5
        when(compensationRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));
        doThrow(new RuntimeException("risk 서버 오류"))
            .when(riskManagementPort).compensate(anyLong(), anyLong(), any());

        service.retryAll();

        verify(compensationRetryRepository, never()).markDone(anyLong());
        verify(compensationRetryRepository, never()).exhaust(anyLong(), anyInt(), any());
        verify(compensationRetryRepository).markRetryLater(
            eq(2L), eq(3), any(LocalDateTime.class), contains("risk 서버 오류"));
    }

    @Test
    @DisplayName("보상 실패 + 최대 시도(5회) 도달 → exhaust 호출")
    void shouldExhaustWhenFailsAndReachesMaxAttempts() {
        PendingCompensation pending = new PendingCompensation(
            3L, 30L, 5L, BigDecimal.valueOf(20_000), 4); // attempt=4, nextAttempt=5 >= MAX(5)
        when(compensationRetryRepository.findDueForRetry(any())).thenReturn(List.of(pending));
        doThrow(new RuntimeException("최종 실패"))
            .when(riskManagementPort).compensate(anyLong(), anyLong(), any());

        service.retryAll();

        verify(compensationRetryRepository, never()).markDone(anyLong());
        verify(compensationRetryRepository, never()).markRetryLater(anyLong(), anyInt(), any(), any());
        verify(compensationRetryRepository).exhaust(eq(3L), eq(5), contains("최종 실패"));
    }

    @Test
    @DisplayName("여러 건이 있을 때 각각 독립적으로 처리된다")
    void shouldProcessEachPendingIndependently() {
        PendingCompensation success = new PendingCompensation(
            1L, 10L, 5L, BigDecimal.valueOf(10_000), 0);
        PendingCompensation fail = new PendingCompensation(
            2L, 20L, 5L, BigDecimal.valueOf(20_000), 0);
        when(compensationRetryRepository.findDueForRetry(any())).thenReturn(List.of(success, fail));
        doNothing().when(riskManagementPort).compensate(eq(10L), anyLong(), any());
        doThrow(new RuntimeException("실패")).when(riskManagementPort).compensate(eq(20L), anyLong(), any());

        service.retryAll();

        verify(compensationRetryRepository).markDone(1L);
        verify(compensationRetryRepository).markRetryLater(eq(2L), eq(1), any(), any());
    }
}
