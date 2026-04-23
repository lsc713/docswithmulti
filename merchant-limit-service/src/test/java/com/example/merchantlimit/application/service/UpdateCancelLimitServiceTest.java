package com.example.merchantlimit.application.service;

import com.example.merchantlimit.application.interfaces.*;
import com.example.merchantlimit.application.usecase.UpdateCancelLimitUseCase;
import com.example.merchantlimit.domain.exception.MerchantNotFoundException;
import com.example.merchantlimit.domain.exception.MerchantSuspendedException;
import com.example.merchantlimit.domain.service.MerchantLimitDomainService;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateCancelLimitService")
class UpdateCancelLimitServiceTest {

    @Mock MerchantRepository merchantRepository;
    @Mock MerchantCancelLimitRepository limitRepository;
    @Mock LimitHistoryRepository historyRepository;
    @Mock LimitEventOutboxRepository outboxRepository;

    UpdateCancelLimitService sut;

    @BeforeEach
    void setUp() {
        sut = new UpdateCancelLimitService(
            merchantRepository, limitRepository,
            historyRepository, outboxRepository,
            new MerchantLimitDomainService()
        );
    }

    @Test
    @DisplayName("한도 변경 성공 — outbox INSERT 확인")
    void execute_updates_limit_and_inserts_outbox() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(1L);
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.of(limit));
        when(limitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateCancelLimitUseCase.Result result =
            sut.execute(1L, BigDecimal.valueOf(8_000_000), "프로모션");

        assertThat(result.dailyLimit()).isEqualByComparingTo(BigDecimal.valueOf(8_000_000));
        verify(historyRepository).save(any());
        verify(outboxRepository).insertPending(eq(1L), anyString());
    }

    @Test
    @DisplayName("한도 미설정이면 신규 생성 후 outbox INSERT")
    void execute_creates_new_limit_when_not_exists() {
        var merchant = MerchantFixture.active();
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.empty());
        when(limitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sut.execute(1L, BigDecimal.valueOf(5_000_000), null);

        verify(limitRepository).save(any());
        verify(outboxRepository).insertPending(eq(1L), anyString());
    }

    @Test
    @DisplayName("가맹점 없으면 404")
    void execute_throws_when_merchant_not_found() {
        when(merchantRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.execute(99L, BigDecimal.valueOf(5_000_000), null))
            .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    @DisplayName("SUSPENDED 가맹점이면 422")
    void execute_throws_when_merchant_suspended() {
        var merchant = MerchantFixture.suspended();
        when(merchantRepository.findById(2L)).thenReturn(Optional.of(merchant));
        assertThatThrownBy(() -> sut.execute(2L, BigDecimal.valueOf(5_000_000), null))
            .isInstanceOf(MerchantSuspendedException.class);
    }
}
