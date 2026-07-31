package com.example.product.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLINK-02 populated-backfill 검증 — 앱 부팅 Flyway 는 빈 DB 에서 V4 를 돌리므로
 * 이 브랜치(레거시 orphan 존재)는 절대 실행하지 않는다. 여기서 target(3)→orphan 삽입→target(4) 로
 * V4 backfill 을 실제로 관통시켜, 빈-vs-채워진 DB 충돌 회귀를 잡는다.
 *
 * @SpringBootTest 금지 (그 Flyway 는 부팅 시 최신까지 자동 마이그레이션). raw 컨테이너 + 프로그래밍 방식 Flyway.
 */
@Testcontainers
@DisplayName("V4 backfill: legacy orphan products land on a level-3 '미분류' leaf, chain built once")
class ProductCategoryMigrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product");

    @Test
    @DisplayName("target(3)→orphan 2건→target(4): 두 행이 level-3 '미분류' leaf 로, 체인은 정확히 1회")
    void populatedBackfill() throws Exception {
        String url = mysql.getJdbcUrl();
        String user = mysql.getUsername();
        String pass = mysql.getPassword();

        // 1. V1~V3 까지만 (product 에 category_id 없음, category 비어있음)
        Flyway.configure().dataSource(url, user, pass).target("3").load().migrate();

        // 2. 레거시 orphan 상품 2건 (category_id 컬럼이 아직 없으므로 name 만)
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO product(name) VALUES ('legacy-a'),('legacy-b')");
        }

        // 3. V4 실행 (backfill 브랜치 발화)
        Flyway.configure().dataSource(url, user, pass).target("4").load().migrate();

        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {

            // (1) 두 레거시 행 모두 level=3 '미분류' 카테고리를 가리킨다
            try (ResultSet rs = st.executeQuery("""
                    SELECT COUNT(*) FROM product p JOIN category cat ON cat.id = p.category_id
                    WHERE cat.level = 3 AND cat.name = '미분류'""")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }

            // (3) category_id 는 NOT NULL — NULL 인 행이 0
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM product WHERE category_id IS NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(0);
            }

            // (2) '미분류' 체인이 정확히 한 번 — level 1/2/3 각 1행, 대>중>소로 연결
            assertThat(count(st, "SELECT COUNT(*) FROM category WHERE name='미분류'")).isEqualTo(3);
            assertThat(count(st, "SELECT COUNT(*) FROM category WHERE name='미분류' AND level=1 AND parent_id IS NULL")).isEqualTo(1);
            assertThat(count(st, """
                    SELECT COUNT(*) FROM category mid
                    JOIN category root ON mid.parent_id = root.id
                    WHERE mid.name='미분류' AND mid.level=2 AND root.name='미분류' AND root.level=1""")).isEqualTo(1);
            assertThat(count(st, """
                    SELECT COUNT(*) FROM category leaf
                    JOIN category mid ON leaf.parent_id = mid.id
                    WHERE leaf.name='미분류' AND leaf.level=3 AND mid.name='미분류' AND mid.level=2""")).isEqualTo(1);
        }

        // 재실행 — 이미 V4 → no-op (Flyway 체크섬 안정성 sanity)
        Flyway.configure().dataSource(url, user, pass).target("4").load().migrate();
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT COUNT(*) FROM category WHERE name='미분류'")).isEqualTo(3);
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
