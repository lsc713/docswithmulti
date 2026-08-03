package com.example.product.domain.entity;

import lombok.Getter;

/** 전역 속성 사전 항목 (순수 POJO — Spring/JPA 어노테이션 금지). */
@Getter
public class Attribute {
    private final Long id;
    private final String name;

    private Attribute(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public static Attribute create(String name) {
        return new Attribute(null, name);
    }

    public static Attribute reconstruct(Long id, String name) {
        return new Attribute(id, name);
    }
}
