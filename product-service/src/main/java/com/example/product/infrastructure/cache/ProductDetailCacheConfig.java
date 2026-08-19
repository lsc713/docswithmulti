package com.example.product.infrastructure.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ProductDetailCacheConfig {
    @Bean
    ProductDetailCachePolicy productDetailCachePolicy(
            @Value("${product.cache.content-soft-ttl-seconds:60}") long softTtlSeconds,
            @Value("${product.cache.content-stale-ttl-seconds:60}") long staleTtlSeconds,
            @Value("${product.cache.ttl-jitter-ratio:0.1}") double jitterRatio) {
        return new ProductDetailCachePolicy(Duration.ofSeconds(softTtlSeconds),
                Duration.ofSeconds(staleTtlSeconds), jitterRatio);
    }
}
