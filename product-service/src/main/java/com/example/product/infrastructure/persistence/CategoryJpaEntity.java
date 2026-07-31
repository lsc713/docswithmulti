package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.Category;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "category")
public class CategoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TINYINT")
    private int level;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // parent_key는 STORED 생성 컬럼 — Hibernate가 INSERT/UPDATE하면 안 되므로 매핑하지 않는다.

    protected CategoryJpaEntity() {}

    public static CategoryJpaEntity from(Category c) {
        CategoryJpaEntity e = new CategoryJpaEntity();
        e.id = c.getId();
        e.parentId = c.getParentId();
        e.name = c.getName();
        e.level = c.getLevel();
        e.createdAt = LocalDateTime.ofInstant(c.getCreatedAt(), ZoneOffset.UTC);
        return e;
    }

    public Category toDomain() {
        return Category.reconstruct(id, parentId, name, level, createdAt.toInstant(ZoneOffset.UTC));
    }
}
