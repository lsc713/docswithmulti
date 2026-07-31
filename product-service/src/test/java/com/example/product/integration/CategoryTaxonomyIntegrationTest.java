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

    private MockHttpServletResponse send(String path, String jsonBody) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse getResp(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse();
    }
}
