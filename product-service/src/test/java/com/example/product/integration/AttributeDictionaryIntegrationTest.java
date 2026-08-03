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
 * 전역 속성 사전 하드닝 (ATTR-01/02/03): 이름/값 중복·없는 속성 거부 + GET 사전 조회.
 * Testcontainers MySQL, WebApplicationContext 하네스 (CategoryTaxonomy 패턴). Docker 필요.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Attribute dictionary (name/value unique, not-found, GET)")
class AttributeDictionaryIntegrationTest {

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
    @DisplayName("ATTR-01: 같은 이름 속성 2번째 → 409 ATTRIBUTE_001")
    void duplicateAttributeNameRejected() throws Exception {
        idOf(postJson("/v1/attributes", """
                {"name":"소재"}"""));
        MockHttpServletResponse dup = postJson("/v1/attributes", """
                {"name":"소재"}""");
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(codeOf(dup)).isEqualTo("ATTRIBUTE_001");
    }

    @Test
    @DisplayName("ATTR-02: 같은 속성에 같은 값 2번째 → 409 ATTRIBUTE_002")
    void duplicateValueRejected() throws Exception {
        long attrId = idOf(postJson("/v1/attributes", """
                {"name":"핏"}"""));
        idOf(postJson("/v1/attributes/" + attrId + "/values", """
                {"value":"슬림"}"""));
        MockHttpServletResponse dup = postJson("/v1/attributes/" + attrId + "/values", """
                {"value":"슬림"}""");
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(codeOf(dup)).isEqualTo("ATTRIBUTE_002");
    }

    @Test
    @DisplayName("ATTR-02: 같은 값이라도 다른 속성이면 허용 → 200")
    void sameValueDifferentAttributeAllowed() throws Exception {
        long a = idOf(postJson("/v1/attributes", """
                {"name":"상의핏"}"""));
        long b = idOf(postJson("/v1/attributes", """
                {"name":"하의핏"}"""));
        idOf(postJson("/v1/attributes/" + a + "/values", """
                {"value":"레귤러"}"""));
        MockHttpServletResponse ok = postJson("/v1/attributes/" + b + "/values", """
                {"value":"레귤러"}""");
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ATTR-03: 없는 attributeId 에 값 추가 → 404 ATTRIBUTE_003")
    void addValueToUnknownAttributeRejected() throws Exception {
        MockHttpServletResponse res = postJson("/v1/attributes/999999/values", """
                {"value":"고아값"}""");
        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(codeOf(res)).isEqualTo("ATTRIBUTE_003");
    }

    @Test
    @DisplayName("ATTR-03: GET /v1/attributes 가 속성·값 전체를 {id,name,values:[{id,value}]} 로 반환")
    void getDictionaryReturnsAll() throws Exception {
        long colorId = idOf(postJson("/v1/attributes", """
                {"name":"색상-dict"}"""));
        long whiteId = idOf(postJson("/v1/attributes/" + colorId + "/values", """
                {"value":"화이트-dict"}"""));
        long blackId = idOf(postJson("/v1/attributes/" + colorId + "/values", """
                {"value":"블랙-dict"}"""));

        MockHttpServletResponse res = mockMvc.perform(get("/v1/attributes")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        Map<?, ?> body = om.readValue(res.getContentAsString(), Map.class);
        List<?> attrs = (List<?>) body.get("attributes");

        Map<?, ?> color = attrs.stream().map(a -> (Map<?, ?>) a)
                .filter(a -> ((Number) a.get("id")).longValue() == colorId)
                .findFirst().orElseThrow();
        assertThat(color.get("name")).isEqualTo("색상-dict");
        List<?> values = (List<?>) color.get("values");
        assertThat(values).hasSize(2);
        // 값 id asc 정렬
        assertThat(((Number) ((Map<?, ?>) values.get(0)).get("id")).longValue()).isEqualTo(whiteId);
        assertThat(((Map<?, ?>) values.get(0)).get("value")).isEqualTo("화이트-dict");
        assertThat(((Number) ((Map<?, ?>) values.get(1)).get("id")).longValue()).isEqualTo(blackId);
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
