package com.example.merchantlimit.application.service;

import com.example.merchantlimit.common.exception.application.MerchantCancelLimitNotFoundException;
import com.example.merchantlimit.application.interfaces.MerchantCancelLimitRepository;
import com.example.merchantlimit.application.interfaces.MerchantRepository;
import com.example.merchantlimit.application.usecase.GetCancelLimitUseCase;
import com.example.merchantlimit.common.exception.domain.MerchantNotFoundException;
import com.example.merchantlimit.fixture.MerchantCancelLimitFixture;
import com.example.merchantlimit.fixture.MerchantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCancelLimitService")
class GetCancelLimitServiceTest {

    @Mock MerchantRepository merchantRepository;
    @Mock MerchantCancelLimitRepository limitRepository;

    GetCancelLimitService sut;

    @BeforeEach
    void setUp() {
        sut = new GetCancelLimitService(merchantRepository, limitRepository);
    }

    @Test
    @DisplayName("정상 조회 — 한도 반환")
    void execute_returns_limit() {
        var merchant = MerchantFixture.active();
        var limit = MerchantCancelLimitFixture.defaultLimit(1L);
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.of(limit));

        GetCancelLimitUseCase.Result result = sut.execute(1L);

        assertThat(result.merchantId()).isEqualTo(1L);
        assertThat(result.dailyLimit()).isEqualByComparingTo(limit.getDailyLimit());
        assertThat(result.merchantStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("가맹점 없으면 404")
    void execute_throws_when_merchant_not_found() {
        when(merchantRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(99L))
            .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    @DisplayName("한도 미설정이면 422")
    void execute_throws_when_limit_not_found() {
        var merchant = MerchantFixture.active();
        when(merchantRepository.findById(1L)).thenReturn(Optional.of(merchant));
        when(limitRepository.findByMerchantId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.execute(1L))
            .isInstanceOf(MerchantCancelLimitNotFoundException.class);
    }
}
