package com.example.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 카테고리 기반 상품 브라우징 end-to-end (PLINK-01, BROWSE-01/02)를 실제 MySQL(Testcontainers)로 관통.
 * Boot 4.0.5: @AutoConfigureMockMvc / TestRestTemplate 미제공 → MockMvcBuilders.webAppContextSetup (CategoryTaxonomy 패턴).
 */
@SpringBootTest
@Testcontainers
@DisplayName("Product browse (link/detail/list aggregation)")
class ProductBrowseIntegrationTest {

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

    @Autowired WebApplicationContext ctx;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    // ---- PLINK-01 / BROWSE-02 (tracer) ----

    @Test
    @DisplayName("tracer: leaf 등록 → GET 상세에 대>중>소 경로 + SKU + availableQty")
    void registerUnderLeafThenReadDetail() throws Exception {
        long leaf = buildTaxonomy("의류", "상의", "티셔츠");

        long productId = registerProduct("""
                {"name":"베이직 티","categoryId":%d,"skus":[{"skuCode":"TS-001","optionSummary":"블랙/M","initialStock":7}]}"""
                .formatted(leaf));

        MockHttpServletResponse res = getResp("/v1/products/" + productId);
        assertThat(res.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(res.getContentAsString(), Map.class);

        List<?> category = (List<?>) body.get("category");
        assertThat(category).hasSize(3);
        assertThat(levelName(category, 0)).containsExactly(1, "의류"); // root
        assertThat(levelName(category, 1)).containsExactly(2, "상의");
        assertThat(levelName(category, 2)).containsExactly(3, "티셔츠"); // leaf

        List<?> skus = (List<?>) body.get("skus");
        assertThat(skus).hasSize(1);
        Map<?, ?> sku0 = (Map<?, ?>) skus.get(0);
        assertThat(sku0.get("skuCode")).isEqualTo("TS-001");
        assertThat(sku0.get("optionSummary")).isEqualTo("블랙/M");
        assertThat(((Number) sku0.get("availableQty")).intValue()).isEqualTo(7);
    }

    @Test
    @DisplayName("PLINK-01: 비-leaf(중분류) categoryId 등록 → 400 PRODUCT_001")
    void registerUnderNonLeafRejected() throws Exception {
        long root = createCategory("""
                {"name":"신발"}""");
        long mid = createCategory("""
                {"parentId":%d,"name":"운동화"}""".formatted(root));

        MockHttpServletResponse res = postProduct("""
                {"name":"불가 상품","categoryId":%d,"skus":[{"skuCode":"X-1","initialStock":1}]}""".formatted(mid));
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("PRODUCT_001");
    }

    @Test
    @DisplayName("PLINK-01: 존재하지 않는 categoryId → 400 PRODUCT_001")
    void registerUnderNonExistentCategoryRejected() throws Exception {
        MockHttpServletResponse res = postProduct("""
                {"name":"고아 상품","categoryId":999999,"skus":[{"skuCode":"X-2","initialStock":1}]}""");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("PRODUCT_001");
    }

    @Test
    @DisplayName("PLINK-01: categoryId 누락 → 400 (INVALID_REQUEST, @NotNull)")
    void registerWithoutCategoryIdRejected() throws Exception {
        MockHttpServletResponse res = postProduct("""
                {"name":"필드 누락","skus":[{"skuCode":"X-3","initialStock":1}]}""");
        assertThat(res.getStatus()).isEqualTo(400); // 코드는 GlobalExceptionHandler INVALID_REQUEST — 상태만 단언
    }

    @Test
    @DisplayName("BROWSE-02: 존재하지 않는 상품 → 404 PRODUCT_002")
    void unknownProductDetail() throws Exception {
        MockHttpServletResponse res = getResp("/v1/products/999999");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("PRODUCT_002");
    }

    // ---- helpers ----

    /** 대>중>소 체인을 만들고 leaf id 반환. */
    long buildTaxonomy(String big, String mid, String small) throws Exception {
        long rootId = createCategory("""
                {"name":"%s"}""".formatted(big));
        long midId = createCategory("""
                {"parentId":%d,"name":"%s"}""".formatted(rootId, mid));
        return createCategory("""
                {"parentId":%d,"name":"%s"}""".formatted(midId, small));
    }

    long createCategory(String jsonBody) throws Exception {
        MockHttpServletResponse res = send("/v1/categories", jsonBody);
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("id")).longValue();
    }

    long registerProduct(String jsonBody) throws Exception {
        MockHttpServletResponse res = postProduct(jsonBody);
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("productId")).longValue();
    }

    MockHttpServletResponse postProduct(String jsonBody) throws Exception {
        return send("/v1/products", jsonBody);
    }

    private List<Object> levelName(List<?> category, int idx) {
        Map<?, ?> node = (Map<?, ?>) category.get(idx);
        return List.of(((Number) node.get("level")).intValue(), node.get("name"));
    }

    String codeOf(MockHttpServletResponse res) throws Exception {
        return (String) om.readValue(res.getContentAsString(), Map.class).get("code");
    }

    MockHttpServletResponse send(String path, String jsonBody) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn().getResponse();
    }

    MockHttpServletResponse getResp(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }
}
