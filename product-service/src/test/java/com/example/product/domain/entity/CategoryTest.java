package com.example.product.domain.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    @DisplayName("루트 카테고리 생성: depth=0, parentId=null")
    void createRoot() {
        Category category = Category.createRoot("상의");

        assertThat(category.getName()).isEqualTo("상의");
        assertThat(category.getParentId()).isNull();
        assertThat(category.getDepth()).isEqualTo(0);
        assertThat(category.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("자식 카테고리 생성: parentId 설정, depth = parent+1")
    void createChild() {
        Category root = Category.createRoot("상의");
        // reconstruct로 id 부여
        Category parent = Category.reconstruct(1L, "상의", null, 0, root.getCreatedAt());

        Category child = Category.createChild("티셔츠", parent.getId(), parent.getDepth());

        assertThat(child.getName()).isEqualTo("티셔츠");
        assertThat(child.getParentId()).isEqualTo(1L);
        assertThat(child.getDepth()).isEqualTo(1);
        assertThat(child.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("카테고리 이름 변경")
    void updateName() {
        Category category = Category.createRoot("상의");
        category.updateName("하의");

        assertThat(category.getName()).isEqualTo("하의");
    }
}
