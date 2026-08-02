package com.example.user.integration;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        r.add("app.admin.bootstrap-emails", () -> "admin@example.com");
    }

    @Autowired WebApplicationContext ctx;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("AUTH-01/02: 신규 signup→중복 409→login, access=USER JWT + opaque refresh 1개 (쿠키 발급)")
    void signupLoginAccessRefreshEndToEnd() throws Exception {
        String email = "alice@example.com";

        // 1. AUTH-01: 신규 이메일 signup → 200, body={"result":"OK"} + access/refresh 쿠키(access=JWT, refresh=opaque UUID)
        MockHttpServletResponse signup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Alice","phone":"010-1111-2222"}""".formatted(email));
        assertThat(signup.getStatus()).isEqualTo(200);
        assertThat(signup.getContentAsString()).isEqualTo("{\"result\":\"OK\"}");
        String signupAccessToken = cookieValue(signup, "access_token");
        String signupRefreshToken = cookieValue(signup, "refresh_token");
        assertThat(signupAccessToken).isNotBlank();
        assertThat(signupRefreshToken).matches("[0-9a-f-]{36}"); // opaque UUID, not JWT

        Claims claims = parse(signupAccessToken);
        long userId = Long.parseLong(claims.getSubject());
        assertThat(userId).isPositive();
        assertThat(claims.get("role", String.class)).isEqualTo("USER");

        // 2. AUTH-01: 같은 이메일 재-signup → 409
        MockHttpServletResponse dup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Alice2","phone":"010-3333-4444"}""".formatted(email));
        assertThat(dup.getStatus()).isEqualTo(409);

        // 4. AUTH-02: login → 200 + access/refresh 쿠키, 사용자당 refresh 1개
        MockHttpServletResponse login = send("/v1/auth/login", """
                {"email":"%s","password":"pw123456"}""".formatted(email));
        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(cookieValue(login, "access_token")).isNotBlank();
        assertThat(cookieValue(login, "refresh_token")).matches("[0-9a-f-]{36}");

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
        String accessToken = cookieValue(signup, "access_token");

        String role = jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
        Long merchantId = jdbc.queryForObject("SELECT merchant_id FROM users WHERE email = ?", Long.class, email);
        assertThat(role).isEqualTo("USER");
        assertThat(merchantId).isNull();

        // 토큰 role 클레임도 USER
        assertThat(parse(accessToken).get("role", String.class)).isEqualTo("USER");
    }

    @Test
    @DisplayName("AUTH-03: 유효 refresh→200 새 access(미회전, refreshToken=null) · 무효 refresh→401")
    void refreshIssuesNewAccessWithoutRotationAndRejectsInvalid() throws Exception {
        String email = "bob@example.com";
        MockHttpServletResponse signup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Bob","phone":"010-5555-6666"}""".formatted(email));
        assertThat(signup.getStatus()).isEqualTo(200);
        String issuedAccessToken = cookieValue(signup, "access_token");
        String issuedRefreshToken = cookieValue(signup, "refresh_token");
        long userId = Long.parseLong(parse(issuedAccessToken).getSubject());

        // 1. 유효 refresh 쿠키 제출 → 200 + body={"result":"OK"} + 새 access 쿠키(미회전, D-P1-1)
        MockHttpServletResponse refreshed = sendRefreshCookie(issuedRefreshToken);
        assertThat(refreshed.getStatus()).isEqualTo(200);
        assertThat(refreshed.getContentAsString()).isEqualTo("{\"result\":\"OK\"}");
        String newAccessToken = cookieValue(refreshed, "access_token");
        assertThat(newAccessToken).isNotBlank();
        assertThat(refreshed.getCookie("refresh_token")).isNull(); // 미회전 — 새 refresh 쿠키 미발급
        assertThat(parse(newAccessToken).getSubject()).isEqualTo(String.valueOf(userId));

        // 미회전 확인 — refresh_tokens 행은 여전히 1개(원래 발급분 그대로)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isEqualTo(1);

        // 2. 조작/미존재 refresh 쿠키 → 401(InvalidToken). (만료 케이스는 AuthServiceTest 단위가 커버)
        MockHttpServletResponse invalid = sendRefreshCookie("00000000-0000-0000-0000-000000000000");
        assertThat(invalid.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("AUTH-04: Bearer access로 logout→200 refresh 하드삭제 · 그 refresh로 재갱신→401")
    void logoutHardDeletesRefreshAndBlocksSubsequentRefresh() throws Exception {
        String email = "carol@example.com";
        send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Carol","phone":"010-7777-8888"}""".formatted(email));
        MockHttpServletResponse login = send("/v1/auth/login", """
                {"email":"%s","password":"pw123456"}""".formatted(email));
        assertThat(login.getStatus()).isEqualTo(200);
        String sessionAccessToken = cookieValue(login, "access_token");
        String sessionRefreshToken = cookieValue(login, "refresh_token");
        long userId = Long.parseLong(parse(sessionAccessToken).getSubject());

        // 3. Bearer access로 logout → 200 (JwtAuthenticationFilter가 principal=userId 세팅 → authenticated 통과)
        // 로그아웃 자체는 쿠키를 만료시키지만, 필터는 여전히 Authorization 헤더만 읽는다(쿠키 인증 전환은 이번 태스크 범위 밖).
        MockHttpServletResponse logout = mockMvc.perform(post("/v1/auth/logout")
                        .header("Authorization", "Bearer " + sessionAccessToken))
                .andReturn().getResponse();
        assertThat(logout.getStatus()).isEqualTo(200);
        assertThat(logout.getCookie("access_token").getMaxAge()).isZero();

        // logout 직후 해당 user의 refresh_tokens 행 0개(하드 DELETE)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        assertThat(count).isZero();

        // 4. 삭제된 refresh 쿠키로 재갱신 → 401 (AUTH-04 무효화 최종 관측)
        MockHttpServletResponse reRefresh = sendRefreshCookie(sessionRefreshToken);
        assertThat(reRefresh.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("ADMIN-BOOTSTRAP: app.admin.bootstrap-emails 이메일로 signup — DB role=ADMIN + JWT 클레임 ADMIN")
    void bootstrapEmailSignupIsPersistedAsAdmin() throws Exception {
        String email = "admin@example.com";

        MockHttpServletResponse signup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Admin","phone":"010-0000-0001"}""".formatted(email));
        assertThat(signup.getStatus()).isEqualTo(200);

        String accessToken = cookieValue(signup, "access_token");
        assertThat(parse(accessToken).get("role", String.class)).isEqualTo("ADMIN");

        String role = jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
        assertThat(role).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("ADMIN-ROLE-01: ADMIN 토큰으로 PATCH /v1/admin/users/{id}/role — 200 + DB 반영, 미존재 404, invalid role 400")
    void adminChangesUserRoleEndToEnd() throws Exception {
        // bootstrap ADMIN 로그인
        send("/v1/auth/signup", """
                {"email":"admin@example.com","password":"pw123456","name":"Admin","phone":"010-0000-0001"}""");
        MockHttpServletResponse adminLogin = send("/v1/auth/login", """
                {"email":"admin@example.com","password":"pw123456"}""");
        String adminAccessToken = cookieValue(adminLogin, "access_token");

        // 일반 유저(승격 대상)
        String targetEmail = "target@example.com";
        MockHttpServletResponse targetSignup = send("/v1/auth/signup", """
                {"email":"%s","password":"pw123456","name":"Target","phone":"010-0000-0002"}""".formatted(targetEmail));
        long targetId = Long.parseLong(parse(cookieValue(targetSignup, "access_token")).getSubject());

        // 200: MERCHANT로 변경
        MockHttpServletResponse promote = mockMvc.perform(patch("/v1/admin/users/" + targetId + "/role")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"MERCHANT"}"""))
                .andReturn().getResponse();
        assertThat(promote.getStatus()).isEqualTo(200);
        String newRole = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, targetId);
        assertThat(newRole).isEqualTo("MERCHANT");

        // 404: 존재하지 않는 유저
        MockHttpServletResponse notFound = mockMvc.perform(patch("/v1/admin/users/999999/role")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"ADMIN"}"""))
                .andReturn().getResponse();
        assertThat(notFound.getStatus()).isEqualTo(404);

        // 400: 알 수 없는 role 값
        MockHttpServletResponse badRequest = mockMvc.perform(patch("/v1/admin/users/" + targetId + "/role")
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"SUPERUSER"}"""))
                .andReturn().getResponse();
        assertThat(badRequest.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("ADMIN-ROLE-02: 비ADMIN 토큰 — 403, 토큰 없음도 403 (SecurityConfig에 명시 authenticationEntryPoint 없어 " +
            "AnonymousAuthenticationFilter+기본 Http403ForbiddenEntryPoint로 귀결 — 기존 전체 모듈 공통, 이번 태스크 범위 밖 기존 동작)")
    void nonAdminIsForbiddenAndAnonymousIsAlsoForbidden() throws Exception {
        MockHttpServletResponse plainSignup = send("/v1/auth/signup", """
                {"email":"plain@example.com","password":"pw123456","name":"Plain","phone":"010-0000-0003"}""");
        String plainAccessToken = cookieValue(plainSignup, "access_token");
        long plainUserId = Long.parseLong(parse(plainAccessToken).getSubject());

        MockHttpServletResponse forbidden = mockMvc.perform(patch("/v1/admin/users/" + plainUserId + "/role")
                        .header("Authorization", "Bearer " + plainAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"ADMIN"}"""))
                .andReturn().getResponse();
        assertThat(forbidden.getStatus()).isEqualTo(403);

        // 토큰 없음: SecurityConfig가 httpBasic/formLogin/명시적 authenticationEntryPoint를 구성하지 않아
        // Spring Security 기본값(Http403ForbiddenEntryPoint)으로 귀결 — 401이 아닌 403. 모듈 전체(anyRequest().authenticated())에
        // 이미 존재하는 동작이라 이번 admin 엔드포인트 범위에서 변경하지 않음(report의 concern 참조).
        MockHttpServletResponse unauthorized = mockMvc.perform(patch("/v1/admin/users/" + plainUserId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"role":"ADMIN"}"""))
                .andReturn().getResponse();
        assertThat(unauthorized.getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse send(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse sendRefreshCookie(String refreshToken) throws Exception {
        return mockMvc.perform(post("/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshToken)))
                .andReturn().getResponse();
    }

    private String cookieValue(MockHttpServletResponse response, String name) {
        var cookie = response.getCookie(name);
        assertThat(cookie).as("cookie[%s]", name).isNotNull();
        return cookie.getValue();
    }

    private Claims parse(String jwt) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(jwt)
                .getPayload();
    }
}
