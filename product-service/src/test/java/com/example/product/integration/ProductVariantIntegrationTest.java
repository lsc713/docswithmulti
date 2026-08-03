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
 * 변형 조합 검증 3분기(완전성/유일성/소속) + 2속성 완전조합 상세 (VAR-02, VQUERY-01).
 * Testcontainers MySQL, WebApplicationContext 하네스. Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Product variant validation (complete/unique/membership + 2-attr detail)")
class ProductVariantIntegrationTest {

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

    long colorId, sizeId, materialId;
    long white, black, sizeM, sizeL, wool;
    long leafId;
    String n; // 공유 컨테이너 → 테스트마다 유일한 이름 접미사 (사전 UK 충돌 방지)

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
        n = "-" + System.nanoTime();
        // 사전: 색상(화이트/블랙), 사이즈(M/L), 소재(울) — 소재는 선언 안 하는 소속-위반 유도용.
        colorId = idOf(postJson("/v1/attributes", "{\"name\":\"색상" + n + "\"}"));
        white = idOf(postJson("/v1/attributes/" + colorId + "/values", "{\"value\":\"화이트\"}"));
        black = idOf(postJson("/v1/attributes/" + colorId + "/values", "{\"value\":\"블랙\"}"));
        sizeId = idOf(postJson("/v1/attributes", "{\"name\":\"사이즈" + n + "\"}"));
        sizeM = idOf(postJson("/v1/attributes/" + sizeId + "/values", "{\"value\":\"M\"}"));
        sizeL = idOf(postJson("/v1/attributes/" + sizeId + "/values", "{\"value\":\"L\"}"));
        materialId = idOf(postJson("/v1/attributes", "{\"name\":\"소재" + n + "\"}"));
        wool = idOf(postJson("/v1/attributes/" + materialId + "/values", "{\"value\":\"울\"}"));
        leafId = createLeafCategory();
    }

    @Test
    @DisplayName("(1) 색상·사이즈 완전조합 [화이트,M]/[화이트,L] 200 + 상세 variantOptions/variant 정확")
    void fullCombinationDetail() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products", product(
                variant(colorId, sizeId),
                sku("V-WM", white, sizeM),
                sku("V-WL", white, sizeL)));
        assertThat(res.getStatus()).isEqualTo(200);
        long pid = ((Number) om.readValue(res.getContentAsString(), Map.class).get("productId")).longValue();

        Map<?, ?> body = detail(pid);
        List<?> opts = (List<?>) body.get("variantOptions");
        assertThat(opts).hasSize(2);
        Map<?, ?> o0 = (Map<?, ?>) opts.get(0);
        Map<?, ?> o1 = (Map<?, ?>) opts.get(1);
        assertThat(o0.get("attribute")).isEqualTo("색상" + n);
        assertThat((List<String>) o0.get("values")).containsExactly("화이트");
        assertThat(o1.get("attribute")).isEqualTo("사이즈" + n);
        assertThat((List<String>) o1.get("values")).containsExactly("M", "L");

        List<?> skus = (List<?>) body.get("skus");
        assertThat(skus).hasSize(2);
        Map<String, String> s0variant = (Map<String, String>) ((Map<?, ?>) skus.get(0)).get("variant");
        assertThat(s0variant).containsEntry("색상" + n, "화이트").containsEntry("사이즈" + n, "M");
        Map<String, String> s1variant = (Map<String, String>) ((Map<?, ?>) skus.get(1)).get("variant");
        assertThat(s1variant).containsEntry("색상" + n, "화이트").containsEntry("사이즈" + n, "L");
    }

    @Test
    @DisplayName("(2) 사이즈 값 빠진 SKU → 400 VARIANT_001")
    void missingVariantAttributeRejected() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products", product(
                variant(colorId, sizeId),
                sku("V-INCOMPLETE", white))); // 사이즈 빠짐
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("VARIANT_001");
    }

    @Test
    @DisplayName("(3) 한 변형 속성에 2값(색상=[화이트,블랙], 색상만 선언) → 400 VARIANT_001 (그룹 크기>1)")
    void twoValuesOneAttributeRejected() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products", product(
                variant(colorId), // 색상만 선언
                sku("V-TWOCOLOR", white, black)));
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("VARIANT_001");
    }

    @Test
    @DisplayName("(4) 동일 조합 [화이트,M] 2 SKU → 409 VARIANT_002")
    void duplicateCombinationRejected() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products", product(
                variant(colorId, sizeId),
                sku("V-DUP1", white, sizeM),
                sku("V-DUP2", white, sizeM)));
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(codeOf(res)).isEqualTo("VARIANT_002");
    }

    @Test
    @DisplayName("(5) 선언 안 한 속성(소재)의 값 id → 400 VARIANT_003")
    void nonDeclaredAttributeValueRejected() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products", product(
                variant(colorId, sizeId),
                sku("V-BADVAL", white, wool))); // 울은 선언 변형 속성(색상/사이즈) 소속 아님
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("VARIANT_003");
    }

    // --- helpers ---

    private String variant(long... attrIds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attrIds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"attributeId\":").append(attrIds[i]).append(",\"isVariant\":true}");
        }
        return sb.toString();
    }

    private String sku(String code, long... valueIds) {
        StringBuilder vids = new StringBuilder();
        for (int i = 0; i < valueIds.length; i++) {
            if (i > 0) vids.append(',');
            vids.append(valueIds[i]);
        }
        return "{\"skuCode\":\"" + code + "\",\"optionSummary\":\"" + code + "\",\"initialStock\":5,"
                + "\"price\":10000,\"variantValueIds\":[" + vids + "]}";
    }

    private String product(String attributesJson, String... skusJson) {
        return "{\"name\":\"변형상품\",\"categoryId\":" + leafId + ",\"attributes\":[" + attributesJson
                + "],\"skus\":[" + String.join(",", skusJson) + "]}";
    }

    private Map<?, ?> detail(long pid) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/v1/products/" + pid)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return om.readValue(res.getContentAsString(), Map.class);
    }

    private long createLeafCategory() throws Exception {
        long root = idOf(postJson("/v1/categories", "{\"name\":\"의류" + n + "\"}"));
        long mid = idOf(postJson("/v1/categories", "{\"parentId\":" + root + ",\"name\":\"셔츠" + n + "\"}"));
        return idOf(postJson("/v1/categories", "{\"parentId\":" + mid + ",\"name\":\"린넨" + n + "\"}"));
    }

    private long idOf(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(200);
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("id")).longValue();
    }

    private String codeOf(MockHttpServletResponse res) throws Exception {
        return (String) om.readValue(res.getContentAsString(), Map.class).get("code");
    }

    private MockHttpServletResponse postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
}
