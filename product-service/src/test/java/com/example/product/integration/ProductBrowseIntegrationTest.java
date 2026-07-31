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

    // ---- BROWSE-01 (category-scoped list aggregation) ----

    @Test
    @DisplayName("BROWSE-01: 대분류 GET → 하위 leaf 상품 2건 최신순 집계")
    void listUnderRootNewestFirst() throws Exception {
        long root = createCategory("""
                {"name":"가방"}""");
        long mid = createCategory("""
                {"parentId":%d,"name":"백팩"}""".formatted(root));
        long leaf = createCategory("""
                {"parentId":%d,"name":"데일리백팩"}""".formatted(mid));

        long p1 = registerProduct(product("가방-A", leaf, "BAG-A"));
        long p2 = registerProduct(product("가방-B", leaf, "BAG-B"));

        Map<?, ?> body = getList("/v1/categories/" + root + "/products");
        List<?> content = (List<?>) body.get("content");
        assertThat(content).hasSize(2);
        // 최신순: 나중에 등록한 p2 가 먼저 (created_at desc, id desc tiebreak)
        assertThat(idAt(content, 0)).isEqualTo(p2);
        assertThat(idAt(content, 1)).isEqualTo(p1);
        assertThat(((Number) body.get("totalElements")).longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("BROWSE-01: 같은 대분류 아래 두 중>소 브랜치 상품 모두 집계")
    void listAggregatesAcrossBranches() throws Exception {
        long root = createCategory("""
                {"name":"악세서리"}""");
        long midA = createCategory("""
                {"parentId":%d,"name":"모자"}""".formatted(root));
        long leafA = createCategory("""
                {"parentId":%d,"name":"버킷햇"}""".formatted(midA));
        long midB = createCategory("""
                {"parentId":%d,"name":"벨트"}""".formatted(root));
        long leafB = createCategory("""
                {"parentId":%d,"name":"가죽벨트"}""".formatted(midB));

        registerProduct(product("햇1", leafA, "HAT-1"));
        registerProduct(product("햇2", leafA, "HAT-2"));
        registerProduct(product("벨트1", leafB, "BELT-1"));

        Map<?, ?> body = getList("/v1/categories/" + root + "/products");
        assertThat((List<?>) body.get("content")).hasSize(3);
        assertThat(((Number) body.get("totalElements")).longValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("BROWSE-01: size=1 페이징 경계 — page0/page1 각 1건, totalElements=2")
    void pagingBoundary() throws Exception {
        long root = createCategory("""
                {"name":"주방"}""");
        long mid = createCategory("""
                {"parentId":%d,"name":"컵"}""".formatted(root));
        long leaf = createCategory("""
                {"parentId":%d,"name":"머그"}""".formatted(mid));

        long p1 = registerProduct(product("머그-A", leaf, "MUG-A"));
        long p2 = registerProduct(product("머그-B", leaf, "MUG-B"));

        Map<?, ?> page0 = getList("/v1/categories/" + root + "/products?page=0&size=1");
        assertThat((List<?>) page0.get("content")).hasSize(1);
        assertThat(idAt((List<?>) page0.get("content"), 0)).isEqualTo(p2); // 최신
        assertThat(((Number) page0.get("totalElements")).longValue()).isEqualTo(2);

        Map<?, ?> page1 = getList("/v1/categories/" + root + "/products?page=1&size=1");
        assertThat((List<?>) page1.get("content")).hasSize(1);
        assertThat(idAt((List<?>) page1.get("content"), 0)).isEqualTo(p1);
    }

    @Test
    @DisplayName("BROWSE-01: 존재하지 않는 카테고리 → 404 CATEGORY_003")
    void unknownCategoryList() throws Exception {
        MockHttpServletResponse res = getResp("/v1/categories/999999/products");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("CATEGORY_003");
    }

    @Test
    @DisplayName("BROWSE-01: 유효하지만 상품 없는 leaf → 200 빈 content")
    void emptyLeafList() throws Exception {
        long leaf = buildTaxonomy("문구", "노트", "스프링노트");
        Map<?, ?> body = getList("/v1/categories/" + leaf + "/products");
        assertThat((List<?>) body.get("content")).isEmpty();
        assertThat(((Number) body.get("totalElements")).longValue()).isZero();
    }

    // ---- helpers ----

    private String product(String name, long categoryId, String skuCode) {
        return """
                {"name":"%s","categoryId":%d,"skus":[{"skuCode":"%s","initialStock":1}]}"""
                .formatted(name, categoryId, skuCode);
    }

    private Map<?, ?> getList(String path) throws Exception {
        MockHttpServletResponse res = getResp(path);
        assertThat(res.getStatus()).isEqualTo(200);
        return om.readValue(res.getContentAsString(), Map.class);
    }

    private long idAt(List<?> content, int idx) {
        return ((Number) ((Map<?, ?>) content.get(idx)).get("id")).longValue();
    }

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
