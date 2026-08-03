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
 * 서술(descriptive) 속성 end-to-end tracer: 사전 → 서술 선언 → descriptiveValueIds 부착 → 상세 specs 노출.
 * 서술 1속성(소재=울) happy path 만 — 400/다값/다속성은 ProductSpecsIntegrationTest 가 확장.
 * 변형(색상=화이트)과 병존해 기존 필드가 보존됨을 함께 단언.
 *
 * Boot 4.0.5: WebApplicationContext + MockMvcBuilders.webAppContextSetup. Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Attribute/specs tracer (dictionary→declare descriptive→attach→detail specs)")
class AttributeSpecsTracerIntegrationTest {

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

    @Test
    @DisplayName("소재=울 서술 1개 end-to-end: 등록 200 + 상세 specs [{소재:[울]}] + variantOptions·기존 필드 병존")
    void tracerEndToEnd() throws Exception {
        // 1. 사전: 색상(변형용), 소재(서술용)
        long colorAttrId = idOf(postJson("/v1/attributes", """
                {"name":"색상-specs"}"""));
        long whiteValueId = idOf(postJson("/v1/attributes/" + colorAttrId + "/values", """
                {"value":"화이트"}"""));
        long materialAttrId = idOf(postJson("/v1/attributes", """
                {"name":"소재-specs"}"""));
        long woolValueId = idOf(postJson("/v1/attributes/" + materialAttrId + "/values", """
                {"value":"울"}"""));

        // 2. leaf 카테고리
        long leafId = createLeafCategory();

        // 3. 상품 등록: 색상=변형 + 소재=서술 선언, descriptiveValueIds:[울]
        MockHttpServletResponse created = postJson("/v1/products", """
                {"name":"울 셔츠","categoryId":%d,
                 "attributes":[{"attributeId":%d,"isVariant":true},{"attributeId":%d,"isVariant":false}],
                 "descriptiveValueIds":[%d],
                 "skus":[{"skuCode":"WOOL-WHITE","optionSummary":"화이트","initialStock":10,
                          "price":49000,"variantValueIds":[%d]}]}"""
                .formatted(leafId, colorAttrId, materialAttrId, woolValueId, whiteValueId));
        assertThat(created.getStatus()).isEqualTo(200);
        long productId = ((Number) om.readValue(created.getContentAsString(), Map.class).get("productId")).longValue();

        // 4. 상세: specs [{소재:[울]}] + variantOptions·기존 필드 병존
        MockHttpServletResponse detail = mockMvc.perform(get("/v1/products/" + productId)).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(detail.getContentAsString(), Map.class);

        List<?> specs = (List<?>) body.get("specs");
        assertThat(specs).hasSize(1);
        Map<?, ?> spec = (Map<?, ?>) specs.get(0);
        assertThat(spec.get("attribute")).isEqualTo("소재-specs");
        assertThat((List<String>) spec.get("values")).containsExactly("울");

        // 병존: variantOptions
        List<?> variantOptions = (List<?>) body.get("variantOptions");
        assertThat(variantOptions).hasSize(1);
        assertThat(((Map<?, ?>) variantOptions.get(0)).get("attribute")).isEqualTo("색상-specs");

        // 병존: per-SKU variant + 기존 필드
        List<?> skus = (List<?>) body.get("skus");
        assertThat(skus).hasSize(1);
        Map<?, ?> sku = (Map<?, ?>) skus.get(0);
        assertThat(((Map<?, ?>) sku.get("variant")).get("색상-specs")).isEqualTo("화이트");
        assertThat(sku.get("optionSummary")).isEqualTo("화이트");
        assertThat(((Number) sku.get("price")).longValue()).isEqualTo(49000L);
        assertThat(((Number) sku.get("availableQty")).intValue()).isEqualTo(10);
        assertThat((List<?>) body.get("category")).isNotEmpty();
    }

    private long createLeafCategory() throws Exception {
        long root = idOf(postJson("/v1/categories", """
                {"name":"의류-specs"}"""));
        long mid = idOf(postJson("/v1/categories", """
                {"parentId":%d,"name":"셔츠-specs"}""".formatted(root)));
        return idOf(postJson("/v1/categories", """
                {"parentId":%d,"name":"울셔츠-specs"}""".formatted(mid)));
    }

    private long idOf(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("id")).longValue();
    }

    private MockHttpServletResponse postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "ADMIN").content(body))
                .andReturn().getResponse();
    }
}
