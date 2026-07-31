package com.example.product.application.service;

import com.example.product.application.interfaces.CategoryRepository;
import com.example.product.domain.entity.Category;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 카테고리 생성 + 트리 조회 (spec §4/§5). */
@Service
public class CategoryService {

    private static final long ROOT_KEY = 0L; // parent_id NULL 그룹 키 (트리 조립용)

    private final CategoryRepository repository;

    public CategoryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public record CreateResult(Long id, int level) {}

    public record TreeNode(Long id, String name, int level, List<TreeNode> children) {}

    @Transactional
    public CreateResult create(Long parentId, String name) {
        // Task 1(tracer): 대분류만. 자식 생성/깊이·부모 검증은 Task 2에서 확장.
        Category saved = repository.save(Category.createRoot(name));
        return new CreateResult(saved.getId(), saved.getLevel());
    }

    @Transactional(readOnly = true)
    public List<TreeNode> getTree() {
        Map<Long, List<Category>> byParent = repository.findAll().stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() == null ? ROOT_KEY : c.getParentId()));
        return build(ROOT_KEY, byParent);
    }

    private List<TreeNode> build(long parentKey, Map<Long, List<Category>> byParent) {
        return byParent.getOrDefault(parentKey, List.of()).stream()
                .sorted(Comparator.comparing(Category::getId))
                .map(c -> new TreeNode(c.getId(), c.getName(), c.getLevel(), build(c.getId(), byParent)))
                .toList();
    }
}
