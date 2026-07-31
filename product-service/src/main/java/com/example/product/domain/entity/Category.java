package com.example.product.domain.entity;

import com.example.product.common.exception.application.CategoryDepthExceededException;
import lombok.Getter;

import java.time.Instant;

/** 순수 POJO (Spring/JPA 어노테이션 금지). level 유도·leaf 판정 규칙 보유 (spec §4). */
@Getter
public class Category {
    private final Long id;
    private final Long parentId;
    private final String name;
    private final int level;
    private final Instant createdAt;

    private Category(Long id, Long parentId, String name, int level, Instant createdAt) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.level = level;
        this.createdAt = createdAt;
    }

    /** 대분류(level 1): parent 없음. */
    public static Category createRoot(String name) {
        return new Category(null, null, name, 1, Instant.now());
    }

    /** 자식: level = parent.level + 1. parent가 leaf(level 3)면 level 4가 되므로 거부. */
    public static Category createChild(Category parent, String name) {
        if (parent.isLeaf()) {
            throw new CategoryDepthExceededException();
        }
        return new Category(null, parent.id, name, parent.level + 1, Instant.now());
    }

    public static Category reconstruct(Long id, Long parentId, String name, int level, Instant createdAt) {
        return new Category(id, parentId, name, level, createdAt);
    }

    /** 소분류(level 3)면 leaf — 자식(level 4) 생성 불가. */
    public boolean isLeaf() {
        return level == 3;
    }
}
