package com.example.product.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ProductDetailCacheService {
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final ProductDetailCachePolicy policy;
    private final Supplier<Long> clock;
    private final Timer cacheReadTimer;
    private final Counter fresh;
    private final Counter stale;
    private final Counter miss;
    private final Counter expired;
    private final Counter fallback;

    @Autowired
    public ProductDetailCacheService(RedissonClient redissonClient,
                                     ObjectMapper objectMapper,
                                     ProductDetailCachePolicy policy,
                                     MeterRegistry meterRegistry) {
        this(redissonClient, objectMapper, policy, System::currentTimeMillis, meterRegistry);
    }

    public ProductDetailCacheService(RedissonClient redissonClient,
                                     ObjectMapper objectMapper,
                                     ProductDetailCachePolicy policy,
                                     Supplier<Long> clock,
                                     MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.clock = clock;
        this.cacheReadTimer = Timer.builder("product.detail.cache.read").register(meterRegistry);
        this.fresh = counter(meterRegistry, "fresh");
        this.stale = counter(meterRegistry, "stale");
        this.miss = counter(meterRegistry, "miss");
        this.expired = counter(meterRegistry, "expired");
        this.fallback = counter(meterRegistry, "fallback");
    }

    public <T> T getOrLoad(Long productId, Class<T> valueType, Supplier<T> loader) {
        RBucket<Object> bucket;
        ProductDetailCachePolicy.Envelope<T> envelope;
        try {
            bucket = redissonClient.getBucket(key(productId));
            if (bucket == null) throw new IllegalStateException("cache bucket unavailable");
            envelope = cacheReadTimer.record(() -> read(bucket, valueType));
        } catch (RuntimeException ignored) {
            fallback.increment();
            return loader.get();
        }

        ProductDetailCacheState state = policy.state(envelope, clock.get());
        record(state);
        try {
            if (state == ProductDetailCacheState.FRESH) return envelope.value();
            if (state == ProductDetailCacheState.STALE) {
                CompletableFuture.runAsync(() -> refresh(productId, valueType, loader));
                return envelope.value();
            }
            return refreshUnderLock(productId, valueType, loader);
        } catch (RuntimeException ignored) {
            return loader.get();
        }
    }

    public void evict(Long productId) {
        RBucket<Object> bucket = redissonClient.getBucket(key(productId));
        if (bucket != null) bucket.delete();
    }

    private <T> T refreshUnderLock(Long productId, Class<T> valueType, Supplier<T> loader) {
        RLock lock = redissonClient.getLock(lockKey(productId));
        try {
            if (!lock.tryLock(100, 5_000, TimeUnit.MILLISECONDS)) return loader.get();
            RBucket<Object> bucket = redissonClient.getBucket(key(productId));
            ProductDetailCachePolicy.Envelope<T> current = cacheReadTimer.record(() -> read(bucket, valueType));
            if (policy.state(current, clock.get()) == ProductDetailCacheState.FRESH) return current.value();
            T value = loader.get();
            write(bucket, value);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loader.get();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private <T> void refresh(Long productId, Class<T> valueType, Supplier<T> loader) {
        try {
            refreshUnderLock(productId, valueType, loader);
        } catch (RuntimeException ignored) {
            // stale data remains available; the next request retries the refresh
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ProductDetailCachePolicy.Envelope<T> read(RBucket<Object> bucket, Class<T> valueType) {
        Object rawValue = bucket.get();
        if (!(rawValue instanceof String json)) return null;
        try {
            ProductDetailCachePolicy.Envelope<?> raw = objectMapper.readValue(json,
                    ProductDetailCachePolicy.Envelope.class);
            return new ProductDetailCachePolicy.Envelope<>(
                    objectMapper.convertValue(raw.value(), valueType), raw.cachedAt(),
                    raw.softExpiresAt(), raw.hardExpiresAt());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private <T> void write(RBucket<Object> bucket, T value) {
        try {
            ProductDetailCachePolicy.Envelope<?> envelope = policy.envelope(value, clock.get());
            String json = objectMapper.writeValueAsString(envelope);
            bucket.set(json, envelope.hardExpiresAt() - clock.get(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // cache failures must not fail the authoritative DB read
        }
    }

    private static String key(Long productId) {
        return "product:detail:" + productId;
    }

    private static String lockKey(Long productId) {
        return "product:detail:lock:" + productId;
    }

    private void record(ProductDetailCacheState state) {
        switch (state) {
            case FRESH -> fresh.increment();
            case STALE -> stale.increment();
            case MISS -> miss.increment();
            case EXPIRED -> expired.increment();
        }
    }

    private static Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("product.detail.cache").tag("outcome", outcome).register(meterRegistry);
    }
}
