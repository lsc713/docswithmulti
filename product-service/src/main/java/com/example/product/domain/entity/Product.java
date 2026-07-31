package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (D-P1-2: Spring/JPA 어노테이션 금지). */
@Getter
public class Product {
    private final Long id;
    private final String name;
    private final Long categoryId; // 소분류(leaf, level 3) — spec §5
    private final Instant createdAt;
    private final Instant updatedAt;

    private Product(Long id, String name, Long categoryId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.categoryId = categoryId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Product create(String name, Long categoryId) {
        Instant now = Instant.now();
        return new Product(null, name, categoryId, now, now);
    }

    public static Product reconstruct(Long id, String name, Long categoryId, Instant createdAt, Instant updatedAt) {
        return new Product(id, name, categoryId, createdAt, updatedAt);
    }
}
