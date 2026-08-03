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
 * 서술 경로 확장 검증 (VAR-03, VQUERY-02): 다값 · 다속성 · 소속 400 · 변형+서술 병존.
 * 프로덕션 배선은 Task 1(3750f60) 완료 — 이 테스트는 negative + multi 케이스만.
 * Testcontainers MySQL, WebApplicationContext 하네스(유일 이름 접미사 n 으로 사전 UK 충돌 회피). Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Product specs (multi-value/multi-attr/membership-400/variant+specs coexist)")
class ProductSpecsIntegrationTest {

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

    long colorId, sizeId, materialId, liningId;
    long white, sizeM, wool, cashmere, rough;
    long leafId;
    String n; // 공유 컨테이너 → 테스트마다 유일한 이름 접미사

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).build();
        n = "-" + System.nanoTime();
        colorId = idOf(postJson("/v1/attributes", "{\"name\":\"색상" + n + "\"}"));
        white = idOf(postJson("/v1/attributes/" + colorId + "/values", "{\"value\":\"화이트\"}"));
        sizeId = idOf(postJson("/v1/attributes", "{\"name\":\"사이즈" + n + "\"}"));
        sizeM = idOf(postJson("/v1/attributes/" + sizeId + "/values", "{\"value\":\"M\"}"));
        materialId = idOf(postJson("/v1/attributes", "{\"name\":\"소재" + n + "\"}"));
        wool = idOf(postJson("/v1/attributes/" + materialId + "/values", "{\"value\":\"울\"}"));
        cashmere = idOf(postJson("/v1/attributes/" + materialId + "/values", "{\"value\":\"캐시미어\"}"));
        liningId = idOf(postJson("/v1/attributes", "{\"name\":\"안감거칠기" + n + "\"}"));
        rough = idOf(postJson("/v1/attributes/" + liningId + "/values", "{\"value\":\"거침\"}"));
        leafId = createLeafCategory();
    }

    @Test
    @DisplayName("(1) 다값: 소재=[울,캐시미어] → specs 그 속성 values [울,캐시미어]")
    void multiValueOneAttribute() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products",
                "{\"name\":\"다값상품\",\"categoryId\":" + leafId
                        + ",\"attributes\":[{\"attributeId\":" + materialId + ",\"isVariant\":false}]"
                        + ",\"descriptiveValueIds\":[" + wool + "," + cashmere + "]"
                        + ",\"skus\":[" + plainSku("MV-1") + "]}");
        assertThat(res.getStatus()).isEqualTo(200);
        long pid = productId(res);

        List<?> specs = (List<?>) detail(pid).get("specs");
        assertThat(specs).hasSize(1);
        Map<?, ?> spec = (Map<?, ?>) specs.get(0);
        assertThat(spec.get("attribute")).isEqualTo("소재" + n);
        assertThat((List<String>) spec.get("values")).containsExactly("울", "캐시미어");
    }

    @Test
    @DisplayName("(2) 다속성: 소재=울 + 안감거칠기=거침 → specs 두 항목")
    void multiAttribute() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products",
                "{\"name\":\"다속성상품\",\"categoryId\":" + leafId
                        + ",\"attributes\":[{\"attributeId\":" + materialId + ",\"isVariant\":false}"
                        + ",{\"attributeId\":" + liningId + ",\"isVariant\":false}]"
                        + ",\"descriptiveValueIds\":[" + wool + "," + rough + "]"
                        + ",\"skus\":[" + plainSku("MA-1") + "]}");
        assertThat(res.getStatus()).isEqualTo(200);
        long pid = productId(res);

        List<?> specs = (List<?>) detail(pid).get("specs");
        assertThat(specs).hasSize(2);
        assertThat(((Map<?, ?>) specs.get(0)).get("attribute")).isEqualTo("소재" + n);
        assertThat((List<String>) ((Map<?, ?>) specs.get(0)).get("values")).containsExactly("울");
        assertThat(((Map<?, ?>) specs.get(1)).get("attribute")).isEqualTo("안감거칠기" + n);
        assertThat((List<String>) ((Map<?, ?>) specs.get(1)).get("values")).containsExactly("거침");
    }

    @Test
    @DisplayName("(3) 소속 위반: 색상(변형으로만 선언) 값을 descriptiveValueIds 로 → 400 DESCRIPTIVE_001")
    void membershipViolationRejected() throws Exception {
        // 색상을 변형으로만 선언(서술 미선언)하고 화이트를 descriptiveValueIds 에 넣음.
        MockHttpServletResponse res = postJson("/v1/products",
                "{\"name\":\"소속위반\",\"categoryId\":" + leafId
                        + ",\"attributes\":[{\"attributeId\":" + colorId + ",\"isVariant\":true}]"
                        + ",\"descriptiveValueIds\":[" + white + "]"
                        + ",\"skus\":[{\"skuCode\":\"BAD-1\",\"optionSummary\":\"화이트\",\"initialStock\":5,"
                        + "\"price\":10000,\"variantValueIds\":[" + white + "]}]}");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(codeOf(res)).isEqualTo("DESCRIPTIVE_001");
    }

    @Test
    @DisplayName("(4) 병존: 변형(색상·사이즈)+서술(소재) → variantOptions·specs 동시 + per-SKU variant·기존필드 잔존")
    void variantAndSpecsCoexist() throws Exception {
        MockHttpServletResponse res = postJson("/v1/products",
                "{\"name\":\"병존상품\",\"categoryId\":" + leafId
                        + ",\"attributes\":[{\"attributeId\":" + colorId + ",\"isVariant\":true}"
                        + ",{\"attributeId\":" + sizeId + ",\"isVariant\":true}"
                        + ",{\"attributeId\":" + materialId + ",\"isVariant\":false}]"
                        + ",\"descriptiveValueIds\":[" + wool + "]"
                        + ",\"skus\":[{\"skuCode\":\"CO-WM\",\"optionSummary\":\"화이트/M\",\"initialStock\":7,"
                        + "\"price\":23000,\"variantValueIds\":[" + white + "," + sizeM + "]}]}");
        assertThat(res.getStatus()).isEqualTo(200);
        long pid = productId(res);

        Map<?, ?> body = detail(pid);

        // specs
        List<?> specs = (List<?>) body.get("specs");
        assertThat(specs).hasSize(1);
        assertThat(((Map<?, ?>) specs.get(0)).get("attribute")).isEqualTo("소재" + n);
        assertThat((List<String>) ((Map<?, ?>) specs.get(0)).get("values")).containsExactly("울");

        // variantOptions
        List<?> opts = (List<?>) body.get("variantOptions");
        assertThat(opts).hasSize(2);
        assertThat(((Map<?, ?>) opts.get(0)).get("attribute")).isEqualTo("색상" + n);
        assertThat(((Map<?, ?>) opts.get(1)).get("attribute")).isEqualTo("사이즈" + n);

        // per-SKU variant + 기존 필드
        List<?> skus = (List<?>) body.get("skus");
        assertThat(skus).hasSize(1);
        Map<?, ?> sku = (Map<?, ?>) skus.get(0);
        Map<String, String> variant = (Map<String, String>) sku.get("variant");
        assertThat(variant).containsEntry("색상" + n, "화이트").containsEntry("사이즈" + n, "M");
        assertThat(sku.get("optionSummary")).isEqualTo("화이트/M");
        assertThat(((Number) sku.get("price")).longValue()).isEqualTo(23000L);
        assertThat(((Number) sku.get("availableQty")).intValue()).isEqualTo(7);
        assertThat((List<?>) body.get("category")).isNotEmpty();
        assertThat((List<?>) body.get("images")).isEmpty();
    }

    // --- helpers ---

    private String plainSku(String code) {
        return "{\"skuCode\":\"" + code + "\",\"optionSummary\":\"" + code + "\",\"initialStock\":5,"
                + "\"price\":10000,\"variantValueIds\":[]}";
    }

    private long productId(MockHttpServletResponse res) throws Exception {
        return ((Number) om.readValue(res.getContentAsString(), Map.class).get("productId")).longValue();
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
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "ADMIN").content(body))
                .andReturn().getResponse();
    }
}
