package com.example.riskmanagement.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisDailyLimitCacheTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    RedisDailyLimitCache sut;

    static final LocalDate TODAY = LocalDate.of(2026, 4, 28);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        sut = new RedisDailyLimitCache(redisTemplate);
    }

    @Test
    void get_returns_value_when_cache_hit() {
        when(valueOps.get("daily_limit:1:2026-04-28")).thenReturn("500000");

        Optional<BigDecimal> result = sut.get(1L, TODAY);

        assertThat(result).isEqualTo(Optional.of(new BigDecimal("500000")));
    }

    @Test
    void get_returns_empty_when_cache_miss() {
        when(valueOps.get("daily_limit:1:2026-04-28")).thenReturn(null);

        Optional<BigDecimal> result = sut.get(1L, TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    void set_stores_value_with_correct_key_and_positive_ttl() {
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        sut.set(1L, TODAY, new BigDecimal("1000000"));

        verify(valueOps).set(eq("daily_limit:1:2026-04-28"), eq("1000000"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive();
    }

    @Test
    void set_uses_min_ttl_when_date_is_in_past() {
        LocalDate PAST = LocalDate.of(2020, 1, 1);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        sut.set(1L, PAST, new BigDecimal("500000"));

        verify(valueOps).set(eq("daily_limit:1:2020-01-01"), eq("500000"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(1));
    }
}
