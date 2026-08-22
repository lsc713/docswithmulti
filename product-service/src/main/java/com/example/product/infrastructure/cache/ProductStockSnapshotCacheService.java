package com.example.product.infrastructure.cache;

import com.example.product.application.interfaces.ProductQueryRepository;
import com.example.product.application.service.ProductStockChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ProductStockSnapshotCacheService {
    private final RedissonClient redissonClient;
    private final ProductQueryRepository repository;
    private final long ttlSeconds;
    private final Counter hit;
    private final Counter miss;
    private final Counter fallback;
    private final Counter write;

    public ProductStockSnapshotCacheService(RedissonClient redissonClient,
                                            ProductQueryRepository repository,
                                            MeterRegistry meterRegistry,
                                            @Value("${product.cache.stock-ttl-seconds:5}") long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.repository = repository;
        this.ttlSeconds = ttlSeconds;
        this.hit = counter(meterRegistry, "hit");
        this.miss = counter(meterRegistry, "miss");
        this.fallback = counter(meterRegistry, "fallback");
        this.write = counter(meterRegistry, "write");
    }

    public Map<Long, Integer> getOrLoad(Long productId) {
        try {
            Map<Long, Integer> cached = bucket(productId).get();
            if (cached != null) {
                hit.increment();
                return cached;
            }
            miss.increment();
        } catch (RuntimeException ignored) {
            fallback.increment();
            return load(productId);
        }
        return loadUnderLock(productId);
    }

    private Map<Long, Integer> loadUnderLock(Long productId) {
        RLock lock;
        try {
            lock = redissonClient.getLock("product:stock:lock:" + productId);
            if (!lock.tryLock(100, 5_000, TimeUnit.MILLISECONDS)) return load(productId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return load(productId);
        } catch (RuntimeException ignored) {
            return load(productId);
        }
        try {
            try {
                Map<Long, Integer> cached = bucket(productId).get();
                if (cached != null) return cached;
            } catch (RuntimeException ignored) {
                // Redis is optional; the authoritative DB read remains available.
            }
            return load(productId);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (RuntimeException ignored) {
                // An expired or unavailable lock must not hide the DB result.
            }
        }
    }

    private Map<Long, Integer> load(Long productId) {
        Map<Long, Integer> fresh = repository.findSkuAvailability(productId);
        write(productId, fresh);
        return fresh;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refreshAfterCommit(ProductStockChangedEvent event) {
        refreshAfterCommit(event.productIds());
    }

    public void refreshAfterCommit(Set<Long> productIds) {
        productIds.forEach(this::refresh);
    }

    private void refresh(Long productId) {
        write(productId, repository.findSkuAvailability(productId));
    }

    private void write(Long productId, Map<Long, Integer> snapshot) {
        try {
            bucket(productId).set(snapshot, ttlSeconds, TimeUnit.SECONDS);
            write.increment();
        } catch (RuntimeException ignored) {
            // Redis is optional for reads; the MySQL snapshot remains authoritative.
        }
    }

    private RBucket<Map<Long, Integer>> bucket(Long productId) {
        return redissonClient.getBucket("product:stock:" + productId);
    }

    private static Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("product.stock.cache").tag("outcome", outcome).register(meterRegistry);
    }
}
