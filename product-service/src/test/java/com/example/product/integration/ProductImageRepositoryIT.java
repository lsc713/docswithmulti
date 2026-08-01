package com.example.product.integration;

import com.example.product.application.interfaces.ProductImageRepository;
import com.example.product.application.service.CatalogService;
import com.example.product.domain.entity.ProductImage;
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

/** product_image 영속화 — save/list/reorder/delete 라운드트립(V6). */
@SpringBootTest
@Testcontainers
@DisplayName("ProductImageRepository 영속화")
class ProductImageRepositoryIT {

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

    @Autowired ProductImageRepository repo;
    @Autowired CatalogService catalogService;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long newProductId() {
        long leafCategoryId = CategoryFixtures.leafId(jdbcTemplate);
        var res = catalogService.seed("이미지테스트상품", leafCategoryId,
                List.of(new CatalogService.SkuSeed("SKU-IMG-" + System.nanoTime(), "F", 1, 1000L)));
        return res.productId();
    }

    @Test
    @DisplayName("save/list/reorder/delete 라운드트립")
    void save_list_and_reorder() {
        Long productId = newProductId();

        var a = repo.save(ProductImage.create(productId, "k/a.jpg", repo.nextSortOrder(productId)));
        var b = repo.save(ProductImage.create(productId, "k/b.jpg", repo.nextSortOrder(productId)));

        assertThat(a.getId()).isNotNull();
        assertThat(b.getId()).isNotNull();
        assertThat(a.getSortOrder()).isZero();
        assertThat(b.getSortOrder()).isEqualTo(1);

        repo.updateOrder(productId, List.of(b.getId(), a.getId()));

        assertThat(repo.findByProductId(productId)).extracting(ProductImage::getS3Key)
                .containsExactly("k/b.jpg", "k/a.jpg");

        assertThat(repo.findByIdAndProductId(a.getId(), productId)).isPresent();

        repo.deleteByIdAndProductId(b.getId(), productId);
        assertThat(repo.findByProductId(productId)).hasSize(1);
    }

    @Test
    @DisplayName("updateOrder 는 다른 product 의 이미지에 영향을 주지 않는다")
    void updateOrder_scoped_to_product() {
        Long productId1 = newProductId();
        Long productId2 = newProductId();

        var img1 = repo.save(ProductImage.create(productId1, "k/p1.jpg", repo.nextSortOrder(productId1)));
        // productId2 는 2장으로 시드해 img2 의 원래 sortOrder 를 1(비영)로 만든다 — 공격 시도의 목표 인덱스(0)와
        // 우연히 같은 값이 되어 스코프 위반이 가려지는 것을 방지(findById 로 잘못 구현해도 0==0 이면 검출 불가).
        repo.save(ProductImage.create(productId2, "k/p2-a.jpg", repo.nextSortOrder(productId2)));
        var img2 = repo.save(ProductImage.create(productId2, "k/p2.jpg", repo.nextSortOrder(productId2)));
        int img2OriginalSortOrder = img2.getSortOrder();
        assertThat(img2OriginalSortOrder).isEqualTo(1);

        // productId2 소유인 img2 의 id 를 productId1 스코프로 claim 시도 — 무시되어야 한다.
        repo.updateOrder(productId1, List.of(img2.getId()));

        assertThat(repo.findByIdAndProductId(img2.getId(), productId2))
                .hasValueSatisfying(v -> assertThat(v.getSortOrder()).isEqualTo(img2OriginalSortOrder));
        assertThat(repo.findByProductId(productId2)).extracting(ProductImage::getSortOrder)
                .containsExactly(0, img2OriginalSortOrder);

        // 혼합 리스트: 자기 소유(img1)만 반영되고 타 product 소유(img2 id)는 무시된다.
        repo.updateOrder(productId1, List.of(img2.getId(), img1.getId()));

        assertThat(repo.findByIdAndProductId(img1.getId(), productId1))
                .hasValueSatisfying(v -> assertThat(v.getSortOrder()).isEqualTo(1));
        assertThat(repo.findByIdAndProductId(img2.getId(), productId2))
                .hasValueSatisfying(v -> assertThat(v.getSortOrder()).isEqualTo(img2OriginalSortOrder));
    }
}
