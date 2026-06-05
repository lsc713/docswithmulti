package com.example.product.domain.entity;

import java.time.LocalDateTime;

/**
 * 카테고리 도메인 엔티티
 *
 * Spring/JPA 어노테이션 없이 순수 Java로 작성한다.
 */
public class Category {

    private Long id;
    private String name;
    private Long parentId;
    private int depth;
    private LocalDateTime createdAt;

    private Category(Long id, String name, Long parentId, int depth, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.depth = depth;
        this.createdAt = createdAt;
    }

    /**
     * 루트 카테고리 생성 (depth=0, parentId=null)
     */
    public static Category createRoot(String name) {
        return new Category(null, name, null, 0, LocalDateTime.now());
    }

    /**
     * 자식 카테고리 생성 (depth = parentDepth + 1)
     */
    public static Category createChild(String name, Long parentId, int parentDepth) {
        return new Category(null, name, parentId, parentDepth + 1, LocalDateTime.now());
    }

    /**
     * DB 조회 후 재구성
     */
    public static Category reconstruct(Long id, String name, Long parentId, int depth, LocalDateTime createdAt) {
        return new Category(id, name, parentId, depth, createdAt);
    }

    public void updateName(String newName) {
        this.name = newName;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
    public int getDepth() { return depth; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
