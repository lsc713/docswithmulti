package com.example.riskmanagement.application.service;

import com.example.riskmanagement.application.interfaces.*;
import com.example.riskmanagement.application.usecase.ValidateAndReserveUseCase;
import com.example.riskmanagement.domain.entity.CancelUsageHistory;
import com.example.riskmanagement.domain.entity.MerchantCancelUsage;
import com.example.riskmanagement.domain.exception.MerchantCancelLimitExceededException;
import com.example.riskmanagement.domain.service.CancelLimitDomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateAndReserveService")
class ValidateAndReserveServiceTest {

    @Mock MerchantCancelUsageRepository usageRepository;
    @Mock CancelUsageHistoryRepository historyRepository;
    @Mock MerchantLimitClient merchantLimitClient;
    @Mock DailyLimitCache dailyLimitCache;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    ValidateAndReserveService sut;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private static final BigDecimal DAILY_LIMIT = BigDecimal.valueOf(5_000_000);
    private static final BigDecimal CANCEL_AMOUNT = BigDecimal.valueOf(300_000);

    @BeforeEach
    void setUp() {
        // TransactionTemplate stub — runs callback inline (no real TX)
        TransactionTemplate txTemplate = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        sut = new ValidateAndReserveService(
            usageRepository, historyRepository,
            merchantLimitClient, dailyLimitCache,
            new CancelLimitDomainService(), redisTemplate, txTemplate);
    }

    @Test
    @DisplayName("Redis hit — DB/HTTP 미호출, 차감 성공")
    void execute_redis_hit_skips_db_and_http() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        ValidateAndReserveUseCase.Result result = sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        assertThat(result.remainingLimit()).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));
        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
        verify(usageRepository).save(any());
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("Redis miss, DB 스냅샷 hit — HTTP 미호출")
    void execute_db_snapshot_hit_skips_http() {
        MerchantCancelUsage existing = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, BigDecimal.ZERO);
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(existing));
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        sut.execute(new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        verify(merchantLimitClient, never()).fetchDailyLimit(anyLong(), any());
    }

    @Test
    @DisplayName("Redis miss, DB miss → HTTP 호출")
    void execute_calls_http_when_no_cache_and_no_snapshot() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.empty());
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.empty());
        when(merchantLimitClient.fetchDailyLimit(1L, TODAY)).thenReturn(DAILY_LIMIT);
        when(usageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());

        sut.execute(new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        verify(merchantLimitClient).fetchDailyLimit(1L, TODAY);
    }

    @Test
    @DisplayName("이중 차감 방어 — cancelRequestId 이미 있으면 no-op")
    void execute_returns_noop_when_already_charged() {
        CancelUsageHistory existing = CancelUsageHistory.record("cr_001", 1L, TODAY, CANCEL_AMOUNT);
        MerchantCancelUsage usage = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, CANCEL_AMOUNT);
        when(historyRepository.findByCancelRequestId("cr_001")).thenReturn(Optional.of(existing));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(usage));

        ValidateAndReserveUseCase.Result result = sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY));

        assertThat(result.usedAmount()).isEqualByComparingTo(CANCEL_AMOUNT);
        verify(usageRepository, never()).save(any()); // 재차감 없음
    }

    @Test
    @DisplayName("한도 초과 — MerchantCancelLimitExceededException")
    void execute_throws_when_limit_exceeded() {
        when(dailyLimitCache.get(1L, TODAY)).thenReturn(Optional.of(DAILY_LIMIT));
        when(historyRepository.findByCancelRequestId(anyString())).thenReturn(Optional.empty());
        MerchantCancelUsage full = MerchantCancelUsage.reconstruct(
            1L, 1L, TODAY, DAILY_LIMIT, BigDecimal.valueOf(4_800_000));
        when(usageRepository.findByMerchantIdAndKstDate(1L, TODAY)).thenReturn(Optional.of(full));

        assertThatThrownBy(() -> sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY)))
            .isInstanceOf(MerchantCancelLimitExceededException.class);
    }

    @Test
    @DisplayName("Redis 락 획득 실패 — ServiceUnavailableException")
    void execute_throws_when_lock_not_acquired() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> sut.execute(
            new ValidateAndReserveUseCase.Command(1L, "cr_001", CANCEL_AMOUNT, TODAY)))
            .isInstanceOf(com.example.riskmanagement.application.exception.ServiceUnavailableException.class);
    }
}
