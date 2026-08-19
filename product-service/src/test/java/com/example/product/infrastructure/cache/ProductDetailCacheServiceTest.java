package com.example.product.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProductDetailCacheServiceTest {

    @Test
    void returns_fresh_cached_value_without_loading() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket("product:detail:1")).thenReturn(bucket);
        when(bucket.get()).thenReturn("{\"value\":\"cached\",\"cachedAt\":0,\"softExpiresAt\":9999999999999,\"hardExpiresAt\":9999999999999}");

        var service = new ProductDetailCacheService(redisson, new ObjectMapper(),
                new ProductDetailCachePolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), 0),
                () -> 1_000L);

        assertThat(service.getOrLoad(1L, String.class, () -> "database")).isEqualTo("cached");
        verify(bucket, never()).set(anyString());
    }

    @Test
    void loads_and_stores_on_cache_miss() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Object> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        when(redisson.getBucket("product:detail:1")).thenReturn(bucket);
        when(redisson.getLock("product:detail:lock:1")).thenReturn(lock);
        when(bucket.get()).thenReturn(null);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        var service = new ProductDetailCacheService(redisson, new ObjectMapper(),
                new ProductDetailCachePolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), 0),
                () -> 1_000L);

        assertThat(service.getOrLoad(1L, String.class, () -> "database")).isEqualTo("database");
        verify(bucket).set(anyString(), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(lock).unlock();
    }
}
