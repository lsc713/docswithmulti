package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateAndReserveService (원자 차감)")
class ValidateAndReserveServiceTest {

    @Mock MerchantCancelUsageRepository usageRepository;
    @Mock CancelUsageHistoryRepository historyRepository;
    @Mock MerchantLimitClient merchantLimitClient;
    @Mock DailyLimitCache dailyLimitCache;

    ValidateAndReserveService sut;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private static final BigDecimal DAILY_LIMIT = BigDecimal.valueOf(5_000_000);
    private static final BigDecimal CANCEL_AMOUNT = BigDecimal.valueOf(300_000);

    @BeforeEach
    void setUp() {
        // TransactionTemplate stub — 콜백을 인라인 실행(실제 TX 없음)
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        sut = new ValidateAndReserveService(
            usageRepository, historyRepository, merchantLimitClient, dailyLimitCache, txTemplate);
    }

    private ValidateAndReserveUseCase.Command cmd() {
        return new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY);
    }

    private static MerchantCancelUsage usage(BigDecimal used) {
        return MerchantCancelUsage.reconstruct(1L, 1L, TODAY, DAILY_LIMIT, used);
    }

    @Test
    @DisplayName("멱등 — cancelRequestId 이미 있으면 재차감/기록 없이 기존 결과 재생")
    void replays_when_already_charged() {
        when(historyRepository.findByCancelRequestId("cr_001"))
            .thenReturn(Optional.of(CancelUsageHistory.record("cr_001", 1L, TODAY, CANCEL_AMOUNT)));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage(CANCEL_AMOUNT)));

        ValidateAndReserveUseCase.Result r = sut.execute(cmd());

        assertThat(r.usedAmount()).isEqualByComparingTo(CANCEL_AMOUNT);
        verify(usageRepository, never()).ensureRow(anyLong(), any(), any());
        verify(usageRepository, never()).tryDeduct(anyLong(), any(), any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Redis hit — HTTP/스냅샷 조회 없이 차감 성공")
    void redis_hit_skips_snapshot_and_http() {
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(usageRepository.tryDeduct(1L, TODAY, CANCEL_AMOUNT)).thenReturn(1);
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage(CANCEL_AMOUNT)));

        ValidateAndReserveUseCase.Result r = sut.execute(cmd());

        assertThat(r.remainingLimit()).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));
        verify(usageRepository).ensureRow(1L, TODAY, DAILY_LIMIT);
        verify(usageRepository).tryDeduct(1L, TODAY, CANCEL_AMOUNT);
        verify(historyRepository).save(any());
        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
    }

    @Test
    @DisplayName("Redis miss, DB 스냅샷 hit — HTTP 미호출")
    void snapshot_hit_skips_http() {
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage(BigDecimal.ZERO)));
        when(usageRepository.tryDeduct(1L, TODAY, CANCEL_AMOUNT)).thenReturn(1);

        sut.execute(cmd());

        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
    }

    @Test
    @DisplayName("Redis miss, 스냅샷 miss → HTTP 호출 + 캐시 set")
    void cache_and_snapshot_miss_calls_http() {
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        // 1st call(스냅샷) empty → HTTP, 2nd call(결과) present
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY))
            .thenReturn(Optional.empty(), Optional.of(usage(CANCEL_AMOUNT)));
        when(merchantLimitClient.fetchDailyLimit(1L, TODAY)).thenReturn(DAILY_LIMIT);
        when(usageRepository.tryDeduct(1L, TODAY, CANCEL_AMOUNT)).thenReturn(1);

        sut.execute(cmd());

        verify(merchantLimitClient).fetchDailyLimit(1L, TODAY);
        verify(dailyLimitCache).set(1L, TODAY, DAILY_LIMIT);
    }

    @Test
    @DisplayName("한도 초과 — tryDeduct=0 이면 MerchantCancelLimitExceededException, 원장 미기록")
    void throws_and_records_nothing_when_limit_exceeded() {
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(usageRepository.tryDeduct(1L, TODAY, CANCEL_AMOUNT)).thenReturn(0);
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY))
            .thenReturn(Optional.of(usage(BigDecimal.valueOf(4_800_000))));

        assertThatThrownBy(() -> sut.execute(cmd()))
            .isInstanceOf(MerchantCancelLimitExceededException.class);
        verify(historyRepository, never()).save(any());
    }
}
