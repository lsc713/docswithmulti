package com.example.product.integration;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재고 계열 통합 테스트용 임의 leaf(level 3) 카테고리 픽스처.
 * 이 테스트들은 재고 로직만 검증(카테고리 무관)이지만, V4 이후 product.category_id 가
 * NOT NULL + leaf 필수(PLINK-01)이므로 seed 에 넘길 유효 leaf id 가 필요하다.
 * 컨테이너(클래스 단위 공유)에서 대>중>소 체인을 1회만 만들고 이후엔 기존 id 를 재사용(멱등).
 */
final class CategoryFixtures {

    private CategoryFixtures() {}

    static long leafId(JdbcTemplate jdbc) {
        Long existing = jdbc.query(
                "SELECT id FROM category WHERE name = 'fx-leaf' AND level = 3",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        jdbc.update("INSERT INTO category(parent_id, name, level) VALUES (NULL, 'fx-root', 1)");
        Long root = jdbc.queryForObject(
                "SELECT id FROM category WHERE name = 'fx-root' AND parent_id IS NULL", Long.class);
        jdbc.update("INSERT INTO category(parent_id, name, level) VALUES (?, 'fx-mid', 2)", root);
        Long mid = jdbc.queryForObject(
                "SELECT id FROM category WHERE name = 'fx-mid' AND parent_id = ?", Long.class, root);
        jdbc.update("INSERT INTO category(parent_id, name, level) VALUES (?, 'fx-leaf', 3)", mid);
        return jdbc.queryForObject(
                "SELECT id FROM category WHERE name = 'fx-leaf' AND parent_id = ?", Long.class, mid);
    }
}
