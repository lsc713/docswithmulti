package com.example.order.config;

import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 테스트 전역 RedissonClient mock (Phase 1 product TestRedissonConfig 미러, RedissonAutoConfiguration 배제 대체).
 * 스케줄러 빈 의존성만 충족 — 실 Redis 없이 @SpringBootTest 컨텍스트 기동.
 * getLock().tryLock() → false 로 스텁해, 혹시 @Scheduled 가 발화해도 run() 이 즉시 skip 하도록 한다.
 *
 * <p>@SpringBootTest 컴포넌트 스캔(com.example.order) 경로에 있어 별도 @Import 없이 모든 통합테스트에 적용.
 */
@Configuration
public class TestRedissonConfig {

    @Bean
    public RedissonClient redissonClient() throws InterruptedException {
        RedissonClient client = Mockito.mock(RedissonClient.class);
        RLock lock = Mockito.mock(RLock.class);
        Mockito.when(client.getLock(Mockito.anyString())).thenReturn(lock);
        Mockito.when(lock.tryLock(Mockito.anyLong(), Mockito.anyLong(), Mockito.any())).thenReturn(false);
        return client;
    }
}
