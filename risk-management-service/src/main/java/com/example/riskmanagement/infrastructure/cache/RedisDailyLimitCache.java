package com.example.riskmanagement.infrastructure.cache;

import com.example.riskmanagement.application.interfaces.DailyLimitCache;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RedisDailyLimitCache implements DailyLimitCache {

    private final StringRedisTemplate redisTemplate;
    private static final Duration TTL = Duration.ofHours(25);

    @Override
    public Optional<BigDecimal> get(long merchantId, LocalDate kstDate) {
        String value = redisTemplate.opsForValue().get(key(merchantId, kstDate));
        return Optional.ofNullable(value).map(BigDecimal::new);
    }

    @Override
    public void set(long merchantId, LocalDate kstDate, BigDecimal limit) {
        redisTemplate.opsForValue().set(key(merchantId, kstDate), limit.toPlainString(), TTL);
    }

    private String key(long merchantId, LocalDate kstDate) {
        return "daily_limit:" + merchantId + ":" + kstDate;
    }
}
