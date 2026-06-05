package com.example.product.infrastructure.persistence;

import com.example.product.domain.entity.Category;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private Long parentId;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private CategoryJpaEntity(Long id, String name, Long parentId, int depth, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.depth = depth;
        this.createdAt = createdAt;
    }

    public static CategoryJpaEntity from(Category category) {
        return new CategoryJpaEntity(
                category.getId(),
                category.getName(),
                category.getParentId(),
                category.getDepth(),
                category.getCreatedAt()
        );
    }

    public Category toDomain() {
        return Category.reconstruct(id, name, parentId, depth, createdAt);
    }
}
