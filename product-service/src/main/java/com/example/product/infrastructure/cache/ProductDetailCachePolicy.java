package com.example.product.infrastructure.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class ProductDetailCachePolicy {
    private final long softTtlMillis;
    private final long staleTtlMillis;
    private final double jitterRatio;

    public ProductDetailCachePolicy(Duration softTtl, Duration staleTtl, double jitterRatio) {
        if (softTtl.isNegative() || softTtl.isZero() || staleTtl.isNegative() || staleTtl.isZero()) {
            throw new IllegalArgumentException("cache TTL must be positive");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitter ratio must be between 0 and 1");
        }
        this.softTtlMillis = softTtl.toMillis();
        this.staleTtlMillis = staleTtl.toMillis();
        this.jitterRatio = jitterRatio;
    }

    public ProductDetailCacheState state(Envelope<?> envelope, long nowMillis) {
        if (envelope == null) return ProductDetailCacheState.MISS;
        if (nowMillis < envelope.softExpiresAt()) return ProductDetailCacheState.FRESH;
        if (nowMillis < envelope.hardExpiresAt()) return ProductDetailCacheState.STALE;
        return ProductDetailCacheState.EXPIRED;
    }

    public long newSoftTtlMillis() {
        long jitter = (long) (softTtlMillis * jitterRatio);
        if (jitter == 0) return softTtlMillis;
        return ThreadLocalRandom.current().nextLong(softTtlMillis - jitter, softTtlMillis + jitter + 1);
    }

    public Envelope<?> envelope(Object value, long nowMillis) {
        long softExpiry = nowMillis + newSoftTtlMillis();
        return new Envelope<>(value, nowMillis, softExpiry, softExpiry + staleTtlMillis);
    }

    public record Envelope<T>(T value, long cachedAt, long softExpiresAt, long hardExpiresAt) {}
}
