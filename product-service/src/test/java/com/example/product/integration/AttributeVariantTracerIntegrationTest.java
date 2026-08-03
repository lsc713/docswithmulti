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
 * 전역 사전 → 변형 선언 → SKU 조합 → 상세 노출을 실제 MySQL(Testcontainers)로 관통하는 얇은 tracer.
 * 변형 속성 1개(색상=화이트) happy path 만 — 검증 분기/2속성은 Dictionary/Variant 통합테스트가 확장.
 *
 * Boot 4.0.5: WebApplicationContext + MockMvcBuilders.webAppContextSetup (CategoryTaxonomy 패턴). Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Attribute/variant tracer (dictionary→declare→sku combo→detail)")
class AttributeVariantTracerIntegrationTest {

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
    @DisplayName("색상=화이트 변형 1개 end-to-end: 등록 200 + 상세에 variantOptions/variant + 기존 필드 병존")
    void tracerEndToEnd() throws Exception {
        // 1. 전역 속성 사전: 색상 → id
        long colorAttrId = idOf(postJson("/v1/attributes", """
                {"name":"색상"}"""));
        assertThat(colorAttrId).isPositive();

        // 2. 값 추가: 화이트 → valueId
        long whiteValueId = idOf(postJson("/v1/attributes/" + colorAttrId + "/values", """
                {"value":"화이트"}"""));
        assertThat(whiteValueId).isPositive();

        // 3. leaf 카테고리(소분류) 준비
        long leafId = createLeafCategory();

        // 4. 상품 등록: 색상을 변형 속성으로 선언 + SKU 조합 [화이트]
        MockHttpServletResponse created = postJson("/v1/products", """
                {"name":"린넨 셔츠","categoryId":%d,
                 "attributes":[{"attributeId":%d,"isVariant":true}],
                 "skus":[{"skuCode":"LINEN-WHITE","optionSummary":"화이트","initialStock":10,
                          "price":39000,"variantValueIds":[%d]}]}"""
                .formatted(leafId, colorAttrId, whiteValueId));
        assertThat(created.getStatus()).isEqualTo(200);
        long productId = ((Number) om.readValue(created.getContentAsString(), Map.class).get("productId")).longValue();

        // 5. 상세: variantOptions + SKU variant + 기존 필드 병존
        MockHttpServletResponse detail = mockMvc.perform(get("/v1/products/" + productId)).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(detail.getContentAsString(), Map.class);

        List<?> variantOptions = (List<?>) body.get("variantOptions");
        assertThat(variantOptions).hasSize(1);
        Map<?, ?> opt = (Map<?, ?>) variantOptions.get(0);
        assertThat(opt.get("attribute")).isEqualTo("색상");
        assertThat((List<String>) opt.get("values")).containsExactly("화이트");

        List<?> skus = (List<?>) body.get("skus");
        assertThat(skus).hasSize(1);
        Map<?, ?> sku = (Map<?, ?>) skus.get(0);
        assertThat(((Map<?, ?>) sku.get("variant")).get("색상")).isEqualTo("화이트");
        // 하위호환: 기존 필드 병존
        assertThat(sku.get("optionSummary")).isEqualTo("화이트");
        assertThat(((Number) sku.get("price")).longValue()).isEqualTo(39000L);
        assertThat(((Number) sku.get("availableQty")).intValue()).isEqualTo(10);
        assertThat((List<?>) body.get("category")).isNotEmpty();
    }

    private long createLeafCategory() throws Exception {
        long root = idOf(postJson("/v1/categories", """
                {"name":"의류-tracer"}"""));
        long mid = idOf(postJson("/v1/categories", """
                {"parentId":%d,"name":"셔츠-tracer"}""".formatted(root)));
        return idOf(postJson("/v1/categories", """
                {"parentId":%d,"name":"린넨셔츠-tracer"}""".formatted(mid)));
    }

    private long idOf(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("id")).longValue();
    }

    private MockHttpServletResponse postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
}
