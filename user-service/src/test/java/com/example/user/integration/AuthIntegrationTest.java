package com.example.user.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tracer end-to-end proof: signup→login→access+refresh 를 실제 MySQL(Testcontainers)로
 * presentation→application→domain←infrastructure→user_db 전 레이어 관통.
 * D-P1-2(role 서버강제)를 회귀로 고정. (Docker 필요 — RESEARCH §Environment)
 *
 * Boot 4.0.5: @AutoConfigureMockMvc / TestRestTemplate 가 test 모듈에서 재배치되어
 * WebApplicationContext + MockMvcBuilders.webAppContextSetup 으로 MockMvc 를 직접 조립
 * (spring-test + spring-security-test 만 사용, Boot 오토컨피그 비의존).
 */
@SpringBootTest
@Testcontainers
@DisplayName("Auth 통합 (signup→login→access+refresh)")
class AuthIntegrationTest {

    static final String JWT_SECRET = "integration-secret-key-must-be-at-least-256-bits-long-hmac-sha256!!";

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("user_db")
            .withUsername("user")
            .withPassword("user");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("jwt.secret", () -> JWT_SECRET);
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    final ObjectMapper om = new ObjectMapper();
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    record TokenResp(String accessToken, String refreshToken) {}

    @Test
    @DisplayName("AUTH-01/02: 신규 signup→중복 409→login, access=USER JWT + opaque refresh 1개")
    void signupLoginAccessRefreshEndToEnd() throws Exception {
        String email = "alice@example.com";

        // 1. AUTH-01: 신규 이메일 signup → 200 + access(JWT) + refresh(opaque UUID)
        MockHttpServletResponse signup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Alice","phone":"010-1111-2222"}""".formatted(email));
        assertThat(signup.getStatus()).isEqualTo(200);
        TokenResp signupBody = om.readValue(signup.getContentAsString(), TokenResp.class);
        assertThat(signupBody.accessToken()).isNotBlank();
        assertThat(signupBody.refreshToken()).matches("[0-9a-f-]{36}"); // opaque UUID, not JWT

        Claims claims = parse(signupBody.accessToken());
        long userId = Long.parseLong(claims.getSubject());
        assertThat(userId).isPositive();
        assertThat(claims.get("role", String.class)).isEqualTo("USER");

        // 2. AUTH-01: 같은 이메일 재-signup → 409
        MockHttpServletResponse dup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Alice2","phone":"010-3333-4444"}""".formatted(email));
        assertThat(dup.getStatus()).isEqualTo(409);

        // 4. AUTH-02: login → 200 + access + refresh, 사용자당 refresh 1개
        MockHttpServletResponse login = send("/v1/auth/login", """
                {"email":"%s","password":"pw123456"}""".formatted(email));
        assertThat(login.getStatus()).isEqualTo(200);
        TokenResp loginBody = om.readValue(login.getContentAsString(), TokenResp.class);
        assertThat(loginBody.accessToken()).isNotBlank();
        assertThat(loginBody.refreshToken()).matches("[0-9a-f-]{36}");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("D-P1-2: 클라 role=ADMIN/merchantId=99 자기지정 무시 → 저장 role=USER, merchant_id=NULL")
    void signupIgnoresClientSuppliedRoleAndMerchantId() throws Exception {
        String email = "mallory@example.com";

        // 3. 특권 필드 억지 주입 — SignupRequest 에 필드가 없어 역직렬화 시 무시(fail-on-unknown=false)
        MockHttpServletResponse signup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Mallory","phone":"010-9999-0000","role":"ADMIN","merchantId":99}"""
                .formatted(email));
        assertThat(signup.getStatus()).isEqualTo(200);
        TokenResp body = om.readValue(signup.getContentAsString(), TokenResp.class);

        String role = jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
        Long merchantId = jdbc.queryForObject("SELECT merchant_id FROM users WHERE email = ?", Long.class, email);
        assertThat(role).isEqualTo("USER");
        assertThat(merchantId).isNull();

        // 토큰 role 클레임도 USER
        assertThat(parse(body.accessToken()).get("role", String.class)).isEqualTo("USER");
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private Claims parse(String jwt) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }
}
