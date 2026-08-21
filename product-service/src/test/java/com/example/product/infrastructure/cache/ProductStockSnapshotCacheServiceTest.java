package com.example.product.infrastructure.cache;

import com.example.product.application.interfaces.ProductQueryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductStockSnapshotCacheServiceTest {

    @Test
    void cache_miss_reads_db_and_writes_snapshot() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(repository.findSkuAvailability(10L)).thenReturn(Map.of(101L, 7));

        var registry = new SimpleMeterRegistry();
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5, true);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
        ArgumentCaptor<Map<Long, Integer>> snapshot = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(bucket).set(snapshot.capture(), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
        assertThat(snapshot.getValue()).containsEntry(101L, 7);
        assertThat(registry.get("product.stock.cache").tag("outcome", "miss").counter().count()).isEqualTo(1);
        assertThat(registry.get("product.stock.cache").tag("outcome", "write").counter().count()).isEqualTo(1);
    }

    @Test
    void redis_failure_falls_back_to_db() {
        RedissonClient redisson = mock(RedissonClient.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.getBucket(anyString())).thenThrow(new RedisException("down"));
        when(repository.findSkuAvailability(10L)).thenReturn(Map.of(101L, 0));

        var registry = new SimpleMeterRegistry();
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5, true);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 0);
        assertThat(registry.get("product.stock.cache").tag("outcome", "fallback").counter().count()).isEqualTo(1);
    }

    @Test
    void cache_hit_returns_snapshot_without_loading_db() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(bucket.get()).thenReturn(Map.of(101L, 7));

        var registry = new SimpleMeterRegistry();
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5, true);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
        org.mockito.Mockito.verifyNoInteractions(repository);
        assertThat(registry.get("product.stock.cache").tag("outcome", "hit").counter().count()).isEqualTo(1);
    }

    @Test
    void redis_failure_does_not_hide_db_error() {
        RedissonClient redisson = mock(RedissonClient.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        IllegalStateException databaseError = new IllegalStateException("db down");
        when(redisson.getBucket(anyString())).thenThrow(new RedisException("down"));
        when(repository.findSkuAvailability(10L)).thenThrow(databaseError);

        var service = new ProductStockSnapshotCacheService(redisson, repository, new SimpleMeterRegistry(), 5, true);

        assertThatThrownBy(() -> service.getOrLoad(10L)).isSameAs(databaseError);
    }

    @Test
    void disabled_after_commit_refresh_does_not_read_db() {
        RedissonClient redisson = mock(RedissonClient.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        var service = new ProductStockSnapshotCacheService(
                redisson, repository, new SimpleMeterRegistry(), 5, false);

        service.refreshAfterCommit(Set.of(10L));

        org.mockito.Mockito.verifyNoInteractions(repository, redisson);
    }
}
