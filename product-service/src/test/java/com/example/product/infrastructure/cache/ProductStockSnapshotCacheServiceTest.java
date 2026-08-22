package com.example.product.infrastructure.cache;

import com.example.product.application.interfaces.ProductQueryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductStockSnapshotCacheServiceTest {

    @Test
    void concurrent_cache_misses_in_one_jvm_share_one_load() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        CyclicBarrier initialReads = new CyclicBarrier(2);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(redisson.getLock("product:stock:lock:10")).thenReturn(lock);
        when(bucket.get()).thenAnswer(ignored -> {
            if (reads.incrementAndGet() <= 2) initialReads.await();
            return null;
        });
        when(lock.tryLock(100, 5_000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(repository.findSkuAvailability(10L)).thenAnswer(ignored -> {
            loadStarted.countDown();
            releaseLoad.await();
            return Map.of(101L, 7);
        });

        var service = new ProductStockSnapshotCacheService(
                redisson, repository, new SimpleMeterRegistry(), 5);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.getOrLoad(10L));
            var second = executor.submit(() -> service.getOrLoad(10L));

            assertThat(loadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                verify(lock, after(200).times(1)).tryLock(100, 5_000, TimeUnit.MILLISECONDS);
            } finally {
                releaseLoad.countDown();
            }
            assertThat(first.get(2, TimeUnit.SECONDS)).containsEntry(101L, 7);
            assertThat(second.get(2, TimeUnit.SECONDS)).containsEntry(101L, 7);
        }
        verify(repository, times(1)).findSkuAvailability(10L);
        verify(lock, times(1)).tryLock(100, 5_000, TimeUnit.MILLISECONDS);
    }

    @Test
    void cache_miss_reads_db_and_writes_snapshot() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(redisson.getLock("product:stock:lock:10")).thenReturn(lock);
        when(lock.tryLock(100, 5_000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(repository.findSkuAvailability(10L)).thenReturn(Map.of(101L, 7));

        var registry = new SimpleMeterRegistry();
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
        ArgumentCaptor<Map<Long, Integer>> snapshot = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(bucket).set(snapshot.capture(), org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.SECONDS));
        org.mockito.Mockito.verify(lock).unlock();
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
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5);

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
        var service = new ProductStockSnapshotCacheService(redisson, repository, registry, 5);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
        org.mockito.Mockito.verifyNoInteractions(repository);
        assertThat(registry.get("product.stock.cache").tag("outcome", "hit").counter().count()).isEqualTo(1);
    }

    @Test
    void cache_miss_rechecks_after_lock_before_loading_db() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket<Map<Long, Integer>> bucket = mock(RBucket.class);
        RLock lock = mock(RLock.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        when(redisson.<Map<Long, Integer>>getBucket("product:stock:10")).thenReturn(bucket);
        when(redisson.getLock("product:stock:lock:10")).thenReturn(lock);
        when(bucket.get()).thenReturn(null, Map.of(101L, 7));
        when(lock.tryLock(100, 5_000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        var service = new ProductStockSnapshotCacheService(
                redisson, repository, new SimpleMeterRegistry(), 5);

        assertThat(service.getOrLoad(10L)).containsEntry(101L, 7);
        org.mockito.Mockito.verifyNoInteractions(repository);
        org.mockito.Mockito.verify(lock).unlock();
    }

    @Test
    void redis_failure_does_not_hide_db_error() {
        RedissonClient redisson = mock(RedissonClient.class);
        ProductQueryRepository repository = mock(ProductQueryRepository.class);
        IllegalStateException databaseError = new IllegalStateException("db down");
        when(redisson.getBucket(anyString())).thenThrow(new RedisException("down"));
        when(repository.findSkuAvailability(10L)).thenThrow(databaseError);

        var service = new ProductStockSnapshotCacheService(redisson, repository, new SimpleMeterRegistry(), 5);

        assertThatThrownBy(() -> service.getOrLoad(10L)).isSameAs(databaseError);
    }
}
