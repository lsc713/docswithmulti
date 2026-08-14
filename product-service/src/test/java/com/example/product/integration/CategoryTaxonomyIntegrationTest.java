package com.example.product.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 카테고리 택소노미 end-to-end (CAT-01/02/03)를 실제 MySQL(Testcontainers)로
 * presentation→application→domain←infrastructure→product_db 전 레이어 관통.
 *
 * Boot 4.0.5: @AutoConfigureMockMvc / TestRestTemplate 미제공 →
 * WebApplicationContext + MockMvcBuilders.webAppContextSetup 으로 MockMvc 직접 조립 (StockTracer 패턴).
 * Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Category taxonomy (create/level/depth/sibling-unique/tree)")
class CategoryTaxonomyIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @Test
    @DisplayName("CAT-01/03 tracer: 대분류 생성(level 1) → GET 트리에 자식 없는 노드로 조회")
    void createRootThenReadTree() throws Exception {
        // 1. CAT-01: parentId 없이 대분류 생성 → 200, {id>0, level:1}
        MockHttpServletResponse created = send("/v1/categories", """
                {"name":"의류"}""");
        assertThat(created.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(created.getContentAsString(), Map.class);
        long id = ((Number) body.get("id")).longValue();
        assertThat(id).isPositive();
        assertThat(((Number) body.get("level")).intValue()).isEqualTo(1);

        // 2. CAT-03: GET 트리에 name "의류" level 1, children 빈 배열로 포함
        MockHttpServletResponse tree = getResp("/v1/categories");
        assertThat(tree.getStatus()).isEqualTo(200);
        List<?> nodes = om.readValue(tree.getContentAsString(), List.class);
        Map<?, ?> root = (Map<?, ?>) nodes.stream()
                .map(n -> (Map<?, ?>) n)
                .filter(n -> "의류".equals(n.get("name")))
                .findFirst().orElseThrow();
        assertThat(((Number) root.get("level")).intValue()).isEqualTo(1);
        assertThat((List<?>) root.get("children")).isEmpty();
    }

    @Test
    @DisplayName("CAT-01: 자식은 parent.level+1 (대1→중2→소3), created id는 JdbcTemplate로 level 검증")
    void childLevelDerivation() throws Exception {
        long root = createId("""
                {"name":"신발"}""");
        long mid = createId("""
                {"parentId":%d,"name":"운동화"}""".formatted(root));
        long leaf = createId("""
                {"parentId":%d,"name":"러닝화"}""".formatted(mid));

        assertThat(dbLevel(root)).isEqualTo(1);
        assertThat(dbLevel(mid)).isEqualTo(2);
        assertThat(dbLevel(leaf)).isEqualTo(3);
    }

    @Test
    @DisplayName("CAT-01: 소분류(level 3) 아래 자식 → 400 CATEGORY_DEPTH_EXCEEDED")
    void depthExceededRejected() throws Exception {
        long root = createId("""
                {"name":"가방"}""");
        long mid = createId("""
                {"parentId":%d,"name":"백팩"}""".formatted(root));
        long leaf = createId("""
                {"parentId":%d,"name":"미니백팩"}""".formatted(mid));

        MockHttpServletResponse res = send("/v1/categories", """
                {"parentId":%d,"name":"불가"}""".formatted(leaf));
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("CATEGORY_001");
    }

    @Test
    @DisplayName("존재하지 않는 parentId → 404 CATEGORY_NOT_FOUND")
    void unknownParentRejected() throws Exception {
        MockHttpServletResponse res = send("/v1/categories", """
                {"parentId":999999,"name":"고아"}""");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("CATEGORY_003");
    }

    @Test
    @DisplayName("CAT-02: 같은 부모 아래 이름 중복 → 409 CATEGORY_NAME_DUPLICATE")
    void duplicateSiblingRejected() throws Exception {
        long root = createId("""
                {"name":"주얼리"}""");
        createId("""
                {"parentId":%d,"name":"목걸이"}""".formatted(root));

        MockHttpServletResponse dup = send("/v1/categories", """
                {"parentId":%d,"name":"목걸이"}""".formatted(root));
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(codeOf(dup)).isEqualTo("CATEGORY_002");
    }

    @Test
    @DisplayName("CAT-02: 대분류 두 개 같은 이름(parentId 없음) → 두 번째 409 (parent_key=0 충돌)")
    void duplicateRootRejected() throws Exception {
        createId("""
                {"name":"중복대분류"}""");

        MockHttpServletResponse dup = send("/v1/categories", """
                {"name":"중복대분류"}""");
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(codeOf(dup)).isEqualTo("CATEGORY_002");
    }

    @Test
    @DisplayName("CAT-02: 같은 이름이라도 부모가 다르면 허용 → 200")
    void sameNameDifferentParentAllowed() throws Exception {
        long a = createId("""
                {"name":"브랜드A"}""");
        long b = createId("""
                {"name":"브랜드B"}""");
        createId("""
                {"parentId":%d,"name":"세일"}""".formatted(a));

        MockHttpServletResponse ok = send("/v1/categories", """
                {"parentId":%d,"name":"세일"}""".formatted(b));
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    private long createId(String jsonBody) throws Exception {
        MockHttpServletResponse res = send("/v1/categories", jsonBody);
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("id")).longValue();
    }

    private int dbLevel(long id) {
        return jdbc.queryForObject("SELECT level FROM category WHERE id = ?", Integer.class, id);
    }

    private String codeOf(MockHttpServletResponse res) throws Exception {
        return (String) om.readValue(res.getContentAsString(), Map.class).get("code");
    }

    private MockHttpServletResponse send(String path, String jsonBody) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "ADMIN")
                        .content(jsonBody))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse getResp(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }
}
