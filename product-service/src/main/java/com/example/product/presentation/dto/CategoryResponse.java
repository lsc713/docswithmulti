package com.example.product.presentation.dto;

import com.example.product.application.service.CategoryService.CreateResult;
import com.example.product.application.service.CategoryService.TreeNode;

import java.util.List;

/** POST /v1/categories 응답 {id, level}. 트리 노드는 중첩 Node record. */
public record CategoryResponse(Long id, int level) {

    public static CategoryResponse from(CreateResult r) {
        return new CategoryResponse(r.id(), r.level());
    }

    /** GET /v1/categories 응답 노드 {id, name, level, children}. */
    public record Node(Long id, String name, int level, List<Node> children) {
        public static Node from(TreeNode t) {
            return new Node(t.id(), t.name(), t.level(),
                    t.children().stream().map(Node::from).toList());
        }
    }
}
