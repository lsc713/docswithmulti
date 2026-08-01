package com.example.product.integration;

import com.example.product.application.service.CatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SKU price 컬럼 배선(V5) — 시드 저장 후 DB 조회로 실가격 확인. */
@SpringBootTest
@Testcontainers
@DisplayName("SKU price 시드 배선")
class SeedPriceIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("product_db")
            .withUsername("product")
            .withPassword("product");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired CatalogService catalogService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("seed 시 price 가 product_sku.price 컬럼에 저장/조회된다")
    void seed_persists_sku_price() {
        long leafCategoryId = CategoryFixtures.leafId(jdbcTemplate);

        var res = catalogService.seed("티셔츠", leafCategoryId,
                List.of(new CatalogService.SkuSeed("SKU-1", "M/블랙", 10, 29000L)));
        Long skuId = res.skus().get(0).skuId();

        long price = jdbcTemplate.queryForObject(
                "SELECT price FROM product_sku WHERE id = ?", Long.class, skuId);
        assertThat(price).isEqualTo(29000L);
    }
}
