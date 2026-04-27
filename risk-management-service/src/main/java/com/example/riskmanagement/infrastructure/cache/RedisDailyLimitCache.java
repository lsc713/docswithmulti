package com.example.riskmanagement.infrastructure.cache;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisDailyLimitCache implements DailyLimitCache {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<BigDecimal> get(long merchantId, LocalDate kstDate) {
        String value = redisTemplate.opsForValue().get(key(merchantId, kstDate));
        return Optional.ofNullable(value).map(BigDecimal::new);
    }

    @Override
    public void set(long merchantId, LocalDate kstDate, BigDecimal limit) {
        Duration ttl = ttlUntilMidnight(kstDate);
        redisTemplate.opsForValue().set(key(merchantId, kstDate), limit.toPlainString(), ttl);
    }

    /**
     * kstDate 자정(다음날 00:00 KST)까지 남은 시간을 TTL로 반환.
     * 자정이 이미 지난 경우(당일 요청이 아닌 경우) 최소 1분으로 보정.
     */
    private Duration ttlUntilMidnight(LocalDate kstDate) {
        ZonedDateTime midnight = kstDate.plusDays(1).atStartOfDay(KST);
        Duration remaining = Duration.between(ZonedDateTime.now(KST), midnight);
        return remaining.compareTo(MIN_TTL) > 0 ? remaining : MIN_TTL;
    }

    private String key(long merchantId, LocalDate kstDate) {
        return "daily_limit:" + merchantId + ":" + kstDate;
    }
}
