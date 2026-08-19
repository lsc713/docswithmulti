package com.example.product.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDetailCachePolicyTest {

    private final ProductDetailCachePolicy policy = new ProductDetailCachePolicy(
            Duration.ofMinutes(1), Duration.ofMinutes(1), 0.0);

    @Test
    void classifies_missing_without_an_envelope() {
        assertThat(policy.state(null, 0L)).isEqualTo(ProductDetailCacheState.MISS);
    }

    @Test
    void classifies_fresh_before_soft_expiry() {
        var cached = new ProductDetailCachePolicy.Envelope<>("value", 0L, 60_000L, 120_000L);

        assertThat(policy.state(cached, 59_999L)).isEqualTo(ProductDetailCacheState.FRESH);
    }

    @Test
    void classifies_stale_between_soft_and_hard_expiry() {
        var cached = new ProductDetailCachePolicy.Envelope<>("value", 0L, 60_000L, 120_000L);

        assertThat(policy.state(cached, 60_000L)).isEqualTo(ProductDetailCacheState.STALE);
        assertThat(policy.state(cached, 119_999L)).isEqualTo(ProductDetailCacheState.STALE);
    }

    @Test
    void classifies_expired_after_hard_expiry() {
        var cached = new ProductDetailCachePolicy.Envelope<>("value", 0L, 60_000L, 120_000L);

        assertThat(policy.state(cached, 120_000L)).isEqualTo(ProductDetailCacheState.EXPIRED);
    }

    @Test
    void adds_bounded_jitter_to_refresh_expiry() {
        var jittered = new ProductDetailCachePolicy(Duration.ofSeconds(60), Duration.ofSeconds(60), 0.1);

        long softTtl = jittered.newSoftTtlMillis();

        assertThat(softTtl).isBetween(54_000L, 66_000L);
    }
}
