package com.example.product.domain.entity;

import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (D-P1-2: Spring/JPA 어노테이션 금지). */
@Getter
public class Product {
    private final Long id;
    private final String name;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Product(Long id, String name, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Product create(String name) {
        Instant now = Instant.now();
        return new Product(null, name, now, now);
    }

    public static Product reconstruct(Long id, String name, Instant createdAt, Instant updatedAt) {
        return new Product(id, name, createdAt, updatedAt);
    }
}
