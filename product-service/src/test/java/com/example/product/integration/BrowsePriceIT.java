package com.example.product.integration;

import com.example.product.application.service.CatalogService;
import com.example.product.application.service.ProductQueryService;
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

/** 목록 minPrice(SKU 최소가) + 상세 SKU price 노출. */
@SpringBootTest
@Testcontainers
@DisplayName("Browse price (list minPrice / detail sku price)")
class BrowsePriceIT {

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
    @Autowired ProductQueryService queryService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void list_returns_min_sku_price() {
        long leafCategoryId = CategoryFixtures.leafId(jdbcTemplate);

        catalogService.seed("투피스", leafCategoryId, List.of(
                new CatalogService.SkuSeed("SKU-HIGH", "M/블랙", 10, 29000L),
                new CatalogService.SkuSeed("SKU-LOW", "S/화이트", 5, 19000L)));

        var page = queryService.listCards(leafCategoryId, 0, 20);
        assertThat(page.getContent().get(0).minPrice()).isEqualTo(19000L);
    }

    @Test
    void detail_returns_each_sku_price() {
        long leafCategoryId = CategoryFixtures.leafId(jdbcTemplate);

        var res = catalogService.seed("셋업", leafCategoryId, List.of(
                new CatalogService.SkuSeed("SKU-A", "M/블랙", 10, 29000L),
                new CatalogService.SkuSeed("SKU-B", "S/화이트", 5, 19000L)));
        Long productId = res.productId();

        var d = queryService.detail(productId);
        assertThat(d.skus()).extracting(ProductQueryService.SkuDetail::price)
                .contains(29000L, 19000L);
    }
}
