package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.common.exception.application.CategoryHasChildrenException;
import com.example.product.common.exception.application.CategoryNotFoundException;
import com.example.product.domain.entity.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    @Test
    @DisplayName("루트 카테고리 생성 - parentId null이면 depth=0")
    void createRoot() {
        // given
        Category saved = Category.reconstruct(1L, "상의", null, 0, LocalDateTime.now());
        given(categoryRepository.save(any())).willReturn(saved);

        // when
        Category result = categoryService.create("상의", null);

        // then
        assertThat(result.getDepth()).isEqualTo(0);
        assertThat(result.getParentId()).isNull();
        verify(categoryRepository).save(any());
    }

    @Test
    @DisplayName("자식 카테고리 생성 - parentDepth+1로 설정")
    void createChild() {
        // given
        Category parent = Category.reconstruct(1L, "상의", null, 0, LocalDateTime.now());
        Category savedChild = Category.reconstruct(2L, "반팔", 1L, 1, LocalDateTime.now());
        given(categoryRepository.findById(1L)).willReturn(Optional.of(parent));
        given(categoryRepository.save(any())).willReturn(savedChild);

        // when
        Category result = categoryService.create("반팔", 1L);

        // then
        assertThat(result.getDepth()).isEqualTo(1);
        assertThat(result.getParentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("자식 카테고리 존재하면 삭제 불가 - CategoryHasChildrenException")
    void deleteWithChildrenThrows() {
        // given
        Category category = Category.reconstruct(1L, "상의", null, 0, LocalDateTime.now());
        given(categoryRepository.findById(1L)).willReturn(Optional.of(category));
        given(categoryRepository.existsByParentId(1L)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> categoryService.delete(1L))
                .isInstanceOf(CategoryHasChildrenException.class);
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 삭제 시 CategoryNotFoundException")
    void deleteNotFoundThrows() {
        // given
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> categoryService.delete(99L))
                .isInstanceOf(CategoryNotFoundException.class);
    }
}
