# 쿠키 기반 인증 전환 + 로그인 데모 프론트엔드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 토큰을 httpOnly 쿠키로 다루는 인증으로 전환하고(user-service 발급 + api-gateway 검증/CSRF/CORS), 이를 실증하는 Vite+React 로그인 데모를 추가한다.

**Architecture:** user-service가 로그인/회원가입/리프레시 응답에서 access/refresh/csrf 쿠키를 `Set-Cookie`로 발급하고 `/v1/auth/me`로 신원을 노출한다. api-gateway는 spring-security 없이 서블릿 필터(CORS·CSRF)와 기존 `JwtTrustHeaderFilter`(쿠키 우선 읽기)로 경계를 지킨다. 프론트는 토큰을 만지지 않고 `credentials:'include'`로만 통신한다.

**Tech Stack:** Java 21 · Spring Boot 3.x · Spring Cloud Gateway MVC(서블릿) · spring-web `CorsFilter`/`OncePerRequestFilter` · JJWT · JUnit5+Mockito+MockMvc / Vite 5 + React 18 (Node 18+)

## Global Constraints

- api-gateway에 `spring-boot-starter-security` 추가 금지 (build.gradle D-P2-2). CORS/CSRF는 spring-web 서블릿 필터로만 구현.
- 쿠키 3종: `access_token`(HttpOnly, Secure, SameSite=Lax, Path=`/`, 세션), `refresh_token`(HttpOnly, Secure, SameSite=Strict, Path=`/v1/auth/refresh`, Max-Age=refreshTokenExpiry), `csrf_token`(non-HttpOnly, Secure, SameSite=Lax, Path=`/`, 세션).
- 로그인/회원가입 응답 body에 토큰 노출 금지 — body는 `{"result":"OK"}`.
- CORS 허용 출처는 **명시 화이트리스트**(`*` 금지) + `Allow-Credentials: true`. 기본 dev 출처 `http://localhost:5173`.
- CSRF: double-submit. 상태변경 메서드(POST/PUT/PATCH/DELETE)에서 쿠키 `csrf_token` == 헤더 `X-CSRF-Token` 검증. 예외 경로: `/v1/auth/login`, `/v1/auth/signup`, `/v1/auth/refresh`.
- 프론트는 Vite dev proxy 사용 금지(실 cross-origin 검증). `dangerouslySetInnerHTML` 금지. `index.html`에 CSP `default-src 'self'`.
- 기존 `JwtTrustHeaderFilter`의 신뢰헤더 strip 동작(스푸핑 방지)은 유지. Bearer 경로는 폴백으로 남긴다(기존 테스트/클라 호환).

---

## 파일 구조

```
user-service (수정/추가):
  presentation/support/AuthCookieFactory.java        [신규] 쿠키 3종 생성/만료
  presentation/controller/AuthController.java        [수정] 쿠키 발급, body {result:OK}, refresh 쿠키화, logout 만료
  presentation/controller/MeController.java          [신규] GET /v1/auth/me
  presentation/dto/MeResponse.java                   [신규]
  application/usecase/UserQueryUseCase.java          [신규] getProfile(long)
  application/service/UserQueryService.java          [신규] UserRepository 기반 구현
  infrastructure/security/JwtAuthenticationFilter.java [수정] access_token 쿠키 읽기 폴백
  src/main/resources/application.yml                 [수정] auth.cookie.* 설정

api-gateway (수정/추가):
  filter/JwtTrustHeaderFilter.java                   [수정] access_token 쿠키 우선 읽기
  config/CorsConfig.java                             [신규] CorsFilter 빈
  filter/CsrfFilter.java                             [신규] double-submit 검증
  config/FilterConfig.java                           [신규] CSRF 필터 등록/순서
  config/RouteConfig.java                            [수정] /v1/auth/me 라우트
  src/main/resources/application.yml                 [수정] gateway.cors.allowed-origins

frontend (신규):
  package.json, vite.config.js, index.html, src/main.jsx, src/App.jsx, src/api.js
```

---

### Task 1: AuthCookieFactory (user-service)

**Files:**
- Create: `user-service/src/main/java/com/example/user/presentation/support/AuthCookieFactory.java`
- Test: `user-service/src/test/java/com/example/user/presentation/support/AuthCookieFactoryTest.java`

**Interfaces:**
- Consumes: `JwtTokenProvider.getRefreshTokenExpiry()` (ms) — refresh 쿠키 Max-Age.
- Produces: `AuthCookieFactory` with `String ACCESS="access_token"`, `REFRESH="refresh_token"`, `CSRF="csrf_token"`; methods `ResponseCookie access(String jwt)`, `ResponseCookie refresh(String token)`, `ResponseCookie csrf(String value)`, `String newCsrfValue()`, `List<ResponseCookie> expireAll()`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.example.user.presentation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthCookieFactory")
class AuthCookieFactoryTest {

    // secure=true, sameSite 기본, domain 없음, refresh 만료 14일(ms)
    private final AuthCookieFactory factory =
            new AuthCookieFactory(true, null, 14L * 24 * 3600 * 1000);

    @Test
    @DisplayName("access 쿠키 — HttpOnly/Secure/SameSite=Lax/세션")
    void accessCookie() {
        ResponseCookie c = factory.access("jwt-token");
        assertThat(c.getName()).isEqualTo("access_token");
        assertThat(c.getValue()).isEqualTo("jwt-token");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.isSecure()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Lax");
        assertThat(c.getPath()).isEqualTo("/");
        assertThat(c.getMaxAge().getSeconds()).isEqualTo(-1); // 세션 쿠키
    }

    @Test
    @DisplayName("refresh 쿠키 — HttpOnly/SameSite=Strict/path 제한/Max-Age")
    void refreshCookie() {
        ResponseCookie c = factory.refresh("rt-uuid");
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getSameSite()).isEqualTo("Strict");
        assertThat(c.getPath()).isEqualTo("/v1/auth/refresh");
        assertThat(c.getMaxAge().getSeconds()).isEqualTo(14L * 24 * 3600);
    }

    @Test
    @DisplayName("csrf 쿠키 — non-HttpOnly(JS 읽기 가능)")
    void csrfCookie() {
        ResponseCookie c = factory.csrf("csrf-val");
        assertThat(c.getName()).isEqualTo("csrf_token");
        assertThat(c.isHttpOnly()).isFalse();
        assertThat(c.isSecure()).isTrue();
    }

    @Test
    @DisplayName("expireAll — 3종 Max-Age=0, path 일치")
    void expireAll() {
        List<ResponseCookie> cookies = factory.expireAll();
        assertThat(cookies).hasSize(3);
        assertThat(cookies).allSatisfy(c -> assertThat(c.getMaxAge().getSeconds()).isEqualTo(0));
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c.getName()).isEqualTo("refresh_token");
            assertThat(c.getPath()).isEqualTo("/v1/auth/refresh");
        });
    }

    @Test
    @DisplayName("newCsrfValue — 매번 다른 난수")
    void csrfValueRandom() {
        assertThat(factory.newCsrfValue()).isNotEqualTo(factory.newCsrfValue());
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :user-service:test --tests "*AuthCookieFactoryTest"` → 컴파일 실패(클래스 없음).

- [ ] **Step 3: 최소 구현**

```java
package com.example.user.presentation.support;

import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 인증 쿠키 3종 생성/만료 일원화. 속성은 설정 주입(secure/sameSite domain). */
public class AuthCookieFactory {

    public static final String ACCESS = "access_token";
    public static final String REFRESH = "refresh_token";
    public static final String CSRF = "csrf_token";
    private static final String REFRESH_PATH = "/v1/auth/refresh";

    private final boolean secure;
    private final String domain;         // null이면 미설정(호스트 전용 쿠키)
    private final long refreshMaxAgeSec;

    public AuthCookieFactory(boolean secure, String domain, long refreshExpiryMs) {
        this.secure = secure;
        this.domain = domain;
        this.refreshMaxAgeSec = refreshExpiryMs / 1000;
    }

    public ResponseCookie access(String jwt) {
        return base(ACCESS, jwt, "/").httpOnly(true).sameSite("Lax").build(); // 세션 쿠키(maxAge 기본 -1)
    }

    public ResponseCookie refresh(String token) {
        return base(REFRESH, token, REFRESH_PATH).httpOnly(true).sameSite("Strict")
                .maxAge(Duration.ofSeconds(refreshMaxAgeSec)).build();
    }

    public ResponseCookie csrf(String value) {
        return base(CSRF, value, "/").httpOnly(false).sameSite("Lax").build();
    }

    public String newCsrfValue() {
        return UUID.randomUUID().toString();
    }

    public List<ResponseCookie> expireAll() {
        return List.of(
                base(ACCESS, "", "/").httpOnly(true).maxAge(0).build(),
                base(REFRESH, "", REFRESH_PATH).httpOnly(true).maxAge(0).build(),
                base(CSRF, "", "/").httpOnly(false).maxAge(0).build());
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value).path(path).secure(secure);
        if (domain != null && !domain.isBlank()) b.domain(domain);
        return b;
    }
}
```

- [ ] **Step 4: 통과 확인** — `./gradlew :user-service:test --tests "*AuthCookieFactoryTest"` → PASS.

- [ ] **Step 5: 커밋** — `git add user-service/src/.../AuthCookieFactory.java user-service/src/test/.../AuthCookieFactoryTest.java && git commit -m "feat(auth): AuthCookieFactory 쿠키 3종 생성/만료"`

---

### Task 2: AuthController 쿠키 발급 (signup/login/logout)

**Files:**
- Modify: `user-service/src/main/java/com/example/user/presentation/controller/AuthController.java`
- Modify: `user-service/src/main/java/com/example/user/infrastructure/config/*` (AuthCookieFactory 빈 등록 — 아래 Step 3에서 위치 명시)
- Modify: `user-service/src/main/resources/application.yml`
- Test: `user-service/src/test/java/com/example/user/presentation/controller/AuthControllerTest.java` (기존 수정)

**Interfaces:**
- Consumes: `AuthCookieFactory`(Task 1), `AuthUseCase.signup/login/logout`.
- Produces: signup/login이 `Set-Cookie` access+refresh+csrf 발급, body `{"result":"OK"}`. logout이 `expireAll()` 쿠키 발급.

- [ ] **Step 1: 기존 테스트를 새 계약으로 수정 (실패 상태로)**

```java
// AuthControllerTest — setUp의 standaloneSetup에 MeController 불필요, AuthController만.
// 팩토리는 실제 인스턴스 주입(순수 객체).
@BeforeEach
void setUp() {
    AuthCookieFactory cookies = new AuthCookieFactory(true, null, 1000L * 60 * 60 * 24 * 14);
    mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authUseCase, cookies)).build();
}

@Test
@DisplayName("POST /v1/auth/login — 쿠키 발급 + body에 토큰 없음")
void loginSetsCookies() throws Exception {
    when(authUseCase.login(any())).thenReturn(new TokenResult("jwt-access", "rt-uuid"));
    mockMvc.perform(post("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"test@example.com","password":"pw123"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.result").value("OK"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(cookie().value("access_token", "jwt-access"))
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().value("refresh_token", "rt-uuid"))
            .andExpect(cookie().httpOnly("csrf_token", false))
            .andExpect(header().stringValues("Set-Cookie",
                    org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("SameSite=Lax"))));
}

@Test
@DisplayName("POST /v1/auth/logout — 쿠키 만료(Max-Age=0)")
void logoutExpiresCookies() throws Exception {
    mockMvc.perform(post("/v1/auth/logout").principal(() -> "42"))  // Authentication principal=userId
            .andExpect(status().isOk())
            .andExpect(cookie().maxAge("access_token", 0));
}
```

> 참고: 기존 `shouldSignup`/`shouldLogin`의 `$.accessToken` 단언은 삭제(계약 변경). logout 테스트의 principal은 컨트롤러가 `authentication.getPrincipal()`로 userId를 읽는 기존 방식과 맞춘다 — 기존 코드가 `(long) principal`이면 테스트도 Long principal로 맞출 것.

- [ ] **Step 2: 실패 확인** — `./gradlew :user-service:test --tests "*AuthControllerTest"` → 컴파일/단언 실패.

- [ ] **Step 3: AuthController 수정 + 팩토리 빈 등록**

```java
// AuthController — 생성자에 AuthCookieFactory 추가, 응답을 쿠키로 전환.
private final AuthUseCase authUseCase;
private final AuthCookieFactory cookies;

public AuthController(AuthUseCase authUseCase, AuthCookieFactory cookies) {
    this.authUseCase = authUseCase;
    this.cookies = cookies;
}

@PostMapping("/signup")
public ResponseEntity<Map<String, String>> signup(@RequestBody @Valid SignupRequest request) {
    TokenResult r = authUseCase.signup(new SignupCommand(
            request.email(), request.password(), request.name(),
            request.phone(), UserRole.USER, null));
    return issue(r);
}

@PostMapping("/login")
public ResponseEntity<Map<String, String>> login(@RequestBody @Valid LoginRequest request) {
    TokenResult r = authUseCase.login(new LoginCommand(request.email(), request.password()));
    return issue(r);
}

@PostMapping("/logout")
public ResponseEntity<Void> logout(Authentication authentication) {
    long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
    authUseCase.logout(userId);
    HttpHeaders headers = new HttpHeaders();
    cookies.expireAll().forEach(c -> headers.add(HttpHeaders.SET_COOKIE, c.toString()));
    return ResponseEntity.ok().headers(headers).build();
}

private ResponseEntity<Map<String, String>> issue(TokenResult r) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.SET_COOKIE, cookies.access(r.accessToken()).toString());
    headers.add(HttpHeaders.SET_COOKIE, cookies.refresh(r.refreshToken()).toString());
    headers.add(HttpHeaders.SET_COOKIE, cookies.csrf(cookies.newCsrfValue()).toString());
    return ResponseEntity.ok().headers(headers).body(Map.of("result", "OK"));
}
```

빈 등록 — user-service의 기존 프레젠테이션/웹 설정 클래스(없으면 `infrastructure/config/WebConfig.java` 신규)에 추가:

```java
@Bean
AuthCookieFactory authCookieFactory(
        JwtTokenProvider jwtTokenProvider,
        @Value("${auth.cookie.secure:true}") boolean secure,
        @Value("${auth.cookie.domain:}") String domain) {
    return new AuthCookieFactory(secure, domain, jwtTokenProvider.getRefreshTokenExpiry());
}
```

`application.yml`에 추가:

```yaml
auth:
  cookie:
    secure: true      # dev localhost도 Chrome은 secure context 허용. HTTP-only 로컬 브라우저면 false.
    domain: ""        # 호스트 전용(비움). 프로덕션 도메인 분리 시 설정.
```

- [ ] **Step 4: 통과 확인** — `./gradlew :user-service:test --tests "*AuthControllerTest"` → PASS.

- [ ] **Step 5: 커밋** — `git commit -am "feat(auth): 로그인/회원가입/로그아웃 쿠키 발급 전환(body 토큰 제거)"`

---

### Task 3: refresh 쿠키화

**Files:**
- Modify: `user-service/.../AuthController.java`
- Test: `user-service/.../AuthControllerTest.java`

**Interfaces:**
- Consumes: `@CookieValue("refresh_token")`, `AuthUseCase.refresh(String)` → 새 access JWT.
- Produces: refresh가 refresh 쿠키를 읽어 새 `access_token` 쿠키 발급, body `{"result":"OK"}`.

- [ ] **Step 1: 실패 테스트**

```java
@Test
@DisplayName("POST /v1/auth/refresh — refresh 쿠키로 새 access 쿠키")
void refreshRotatesAccess() throws Exception {
    when(authUseCase.refresh("rt-uuid")).thenReturn("new-access-jwt");
    mockMvc.perform(post("/v1/auth/refresh").cookie(new jakarta.servlet.http.Cookie("refresh_token", "rt-uuid")))
            .andExpect(status().isOk())
            .andExpect(cookie().value("access_token", "new-access-jwt"))
            .andExpect(jsonPath("$.result").value("OK"));
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :user-service:test --tests "*AuthControllerTest.refreshRotatesAccess"` → FAIL.

- [ ] **Step 3: 구현** (기존 body 기반 refresh 대체)

```java
@PostMapping("/refresh")
public ResponseEntity<Map<String, String>> refresh(@CookieValue("refresh_token") String refreshToken) {
    String accessToken = authUseCase.refresh(refreshToken);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.SET_COOKIE, cookies.access(accessToken).toString());
    return ResponseEntity.ok().headers(headers).body(Map.of("result", "OK"));
}
```

> `RefreshRequest` DTO는 더 이상 쓰지 않으면 삭제. import 정리.

- [ ] **Step 4: 통과 확인** — `./gradlew :user-service:test --tests "*AuthControllerTest"` → PASS.

- [ ] **Step 5: 커밋** — `git commit -am "feat(auth): refresh 엔드포인트 쿠키화(refresh 쿠키→access 쿠키 rotation)"`

---

### Task 4: GET /v1/auth/me (프로필 조회)

**Files:**
- Create: `user-service/.../application/usecase/UserQueryUseCase.java`
- Create: `user-service/.../application/service/UserQueryService.java`
- Create: `user-service/.../presentation/dto/MeResponse.java`
- Create: `user-service/.../presentation/controller/MeController.java`
- Modify: `user-service/.../infrastructure/config/PersistenceConfig.java` (UserQueryService 빈; 기존 UserRepository 빈 재사용)
- Test: `user-service/src/test/java/com/example/user/presentation/controller/MeControllerTest.java`
- Test: `user-service/src/test/java/com/example/user/application/service/UserQueryServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findById(long)` → `Optional<User>` (기존).
- Produces: `MeResponse(long userId, String email, String name, String role)`; `UserQueryUseCase.getProfile(long userId)`.

- [ ] **Step 1: UserQueryService 실패 테스트**

```java
package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.domain.entity.User;
import com.example.user.domain.entity.UserRole;
import com.example.user.presentation.dto.MeResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {
    @Mock UserRepository userRepository;

    @Test
    void getProfile_mapsUserToMeResponse() {
        User u = User.reconstruct(42L, "a@b.com", "hash", "홍길동", "010", UserRole.USER, null,
                com.example.user.domain.entity.UserStatus.ACTIVE, null, null);
        when(userRepository.findById(42L)).thenReturn(Optional.of(u));

        MeResponse r = new UserQueryService(userRepository).getProfile(42L);

        assertThat(r.userId()).isEqualTo(42L);
        assertThat(r.email()).isEqualTo("a@b.com");
        assertThat(r.name()).isEqualTo("홍길동");
        assertThat(r.role()).isEqualTo("USER");
    }
}
```

> `User.reconstruct` 시그니처는 실제 정의를 따를 것(위 인자 수/타입은 도메인에 맞춰 조정 — Step 3 전에 `User.java` 확인).

- [ ] **Step 2: 실패 확인** — `./gradlew :user-service:test --tests "*UserQueryServiceTest"` → 컴파일 실패.

- [ ] **Step 3: 구현**

```java
// MeResponse.java
package com.example.user.presentation.dto;
public record MeResponse(long userId, String email, String name, String role) {}
```
```java
// UserQueryUseCase.java
package com.example.user.application.usecase;
import com.example.user.presentation.dto.MeResponse;
public interface UserQueryUseCase {
    MeResponse getProfile(long userId);
}
```
```java
// UserQueryService.java
package com.example.user.application.service;

import com.example.user.application.interfaces.UserRepository;
import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.domain.entity.User;
import com.example.user.presentation.dto.MeResponse;

public class UserQueryService implements UserQueryUseCase {
    private final UserRepository userRepository;
    public UserQueryService(UserRepository userRepository) { this.userRepository = userRepository; }

    @Override
    public MeResponse getProfile(long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("user not found: " + userId));
        return new MeResponse(u.getId(), u.getEmail(), u.getName(), u.getRole().name());
    }
}
```
빈 등록(PersistenceConfig 또는 서비스 설정):
```java
@Bean
UserQueryUseCase userQueryUseCase(UserRepository userRepository) {
    return new UserQueryService(userRepository);
}
```

- [ ] **Step 4: MeController 실패 테스트 + 구현**

```java
// MeControllerTest.java
@ExtendWith(MockitoExtension.class)
class MeControllerTest {
    MockMvc mockMvc;
    @Mock UserQueryUseCase userQuery;

    @BeforeEach void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MeController(userQuery)).build();
    }

    @Test
    void me_returnsProfile() throws Exception {
        when(userQuery.getProfile(42L)).thenReturn(new MeResponse(42L, "a@b.com", "홍길동", "USER"));
        mockMvc.perform(get("/v1/auth/me").principal(() -> "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }
}
```
```java
// MeController.java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.presentation.dto.MeResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class MeController {
    private final UserQueryUseCase userQuery;
    public MeController(UserQueryUseCase userQuery) { this.userQuery = userQuery; }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        long userId = Long.parseLong(String.valueOf(authentication.getPrincipal()));
        return userQuery.getProfile(userId);
    }
}
```

- [ ] **Step 5: 통과 확인 + 커밋** — `./gradlew :user-service:test --tests "*UserQueryServiceTest" --tests "*MeControllerTest"` → PASS. `git add -A && git commit -m "feat(auth): GET /v1/auth/me 프로필 조회"`

---

### Task 5: user-service JwtAuthenticationFilter 쿠키 읽기

**Files:**
- Modify: `user-service/.../infrastructure/security/JwtAuthenticationFilter.java`
- Test: `user-service/src/test/java/com/example/user/infrastructure/security/JwtAuthenticationFilterTest.java`

**Interfaces:**
- Produces: `resolveToken`이 `Authorization: Bearer` 없으면 `access_token` 쿠키에서 JWT를 읽는다.

- [ ] **Step 1: 실패 테스트**

```java
package com.example.user.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    @Test
    void authenticatesFromAccessTokenCookie() throws Exception {
        JwtTokenProvider provider = mock(JwtTokenProvider.class);
        when(provider.validateToken("cookie-jwt")).thenReturn(true);
        when(provider.getUserId("cookie-jwt")).thenReturn(42L);
        when(provider.getRole("cookie-jwt")).thenReturn("USER");
        when(provider.getMerchantId("cookie-jwt")).thenReturn(null);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("access_token", "cookie-jwt"));  // Authorization 헤더 없음

        new JwtAuthenticationFilter(provider)
                .doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(42L);
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :user-service:test --tests "*JwtAuthenticationFilterTest"` → FAIL(쿠키 미지원).

- [ ] **Step 3: 구현** — `resolveToken`에 쿠키 폴백 추가

```java
private String resolveToken(HttpServletRequest request) {
    String bearer = request.getHeader("Authorization");
    if (bearer != null && bearer.startsWith("Bearer ")) {
        return bearer.substring(7);
    }
    if (request.getCookies() != null) {
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if ("access_token".equals(c.getName())) return c.getValue();
        }
    }
    return null;
}
```

- [ ] **Step 4: 통과 확인 + 커밋** — PASS 후 `git commit -am "feat(auth): user-service JWT 필터 access_token 쿠키 폴백"`

---

### Task 6: gateway JwtTrustHeaderFilter 쿠키 우선 읽기

**Files:**
- Modify: `api-gateway/src/main/java/com/example/gateway/filter/JwtTrustHeaderFilter.java`
- Test: `api-gateway/src/test/java/com/example/gateway/filter/JwtTrustHeaderFilterCookieTest.java`

**Interfaces:**
- Produces: 필터가 `access_token` 쿠키에서 JWT를 우선 읽고, 없으면 `Authorization: Bearer` 폴백. 검증 성공 시 기존대로 신뢰헤더 주입.

- [ ] **Step 1: 실패 테스트** (JwtVerifierTest의 token 헬퍼 재사용 스타일)

```java
package com.example.gateway.filter;

import com.example.gateway.config.JwtVerifier;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTrustHeaderFilterCookieTest {
    static final String SECRET = "default-dev-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256";

    static String jwt(long uid, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject(String.valueOf(uid)).claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000)).signWith(key).compact();
    }

    @Test
    void readsTokenFromCookie_injectsTrustHeaders() throws Exception {
        JwtTrustHeaderFilter filter = new JwtTrustHeaderFilter(new JwtVerifier(SECRET));
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/v1/auth/me");
        raw.setCookies(new jakarta.servlet.http.Cookie("access_token", jwt(42L, "USER")));
        ServerRequest req = ServerRequest.create(raw, java.util.List.of());

        final String[] seenUserId = new String[1];
        ServerResponse resp = filter.filter(req, r -> {
            seenUserId[0] = r.headers().firstHeader(JwtTrustHeaderFilter.H_USER_ID);
            return ServerResponse.ok().build();
        });

        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenUserId[0]).isEqualTo("42");
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew :api-gateway:test --tests "*JwtTrustHeaderFilterCookieTest"` → FAIL(쿠키 미읽음 → TOKEN_MISSING 401).

- [ ] **Step 3: 구현** — Bearer 추출부를 쿠키 우선으로 교체

```java
// filter() 내 "2. Bearer 추출" 블록을 아래로 교체
String token = tokenFromCookie(request);
if (token == null) {
    String auth = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
    if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7);
}
if (token == null) {
    return unauthorized("TOKEN_MISSING");
}
// 3. 검증: verifier.parse(token) — 기존 그대로(auth.substring(7) → token 변수로 변경)
```
```java
// 헬퍼 추가
private String tokenFromCookie(ServerRequest request) {
    var cookies = request.cookies().get("access_token");
    return (cookies != null && !cookies.isEmpty()) ? cookies.get(0).getValue() : null;
}
```

- [ ] **Step 4: 통과 확인 + 기존 회귀** — `./gradlew :api-gateway:test` (기존 Bearer 테스트도 그린 유지) → PASS.

- [ ] **Step 5: 커밋** — `git commit -am "feat(gateway): JWT 필터 access_token 쿠키 우선 읽기(Bearer 폴백)"`

---

### Task 7: gateway CORS (CorsFilter, 우회 없이 정식)

**Files:**
- Create: `api-gateway/src/main/java/com/example/gateway/config/CorsConfig.java`
- Modify: `api-gateway/src/main/resources/application.yml`
- Test: `api-gateway/src/test/java/com/example/gateway/config/CorsConfigTest.java`

**Interfaces:**
- Produces: `CorsFilter` 빈 — 허용 출처 화이트리스트 + credentials + `X-CSRF-Token` 허용 헤더 + preflight.

- [ ] **Step 1: 실패 테스트** (CorsConfiguration 매핑 검증 — 순수 단위)

```java
package com.example.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @Test
    void allowsWhitelistedOriginWithCredentials() {
        var source = new CorsConfig().corsConfigurationSource(List.of("http://localhost:5173"));
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/v1/auth/login");
        req.addHeader("Origin", "http://localhost:5173");

        CorsConfiguration cfg = source.getCorsConfiguration(req);

        assertThat(cfg.getAllowCredentials()).isTrue();
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(cfg.checkOrigin("http://evil.com")).isNull();
        assertThat(cfg.getAllowedHeaders()).contains("X-CSRF-Token", "Content-Type");
    }
}
```

- [ ] **Step 2: 실패 확인** — FAIL(클래스 없음).

- [ ] **Step 3: 구현**

```java
package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/** 게이트웨이 CORS(우회 없이 정식). spring-security 미사용 — spring-web CorsFilter. */
@Configuration
public class CorsConfig {

    public UrlBasedCorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);              // 명시 화이트리스트(*금지)
        cfg.setAllowCredentials(true);
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    CorsFilter corsFilter(@Value("${gateway.cors.allowed-origins}") List<String> allowedOrigins) {
        return new CorsFilter(corsConfigurationSource(allowedOrigins));
    }

    // CorsFilter는 CSRF/라우팅보다 먼저 실행돼야 preflight가 단락됨 → 등록 순서는 FilterConfig(Task 8)에서 보장.
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
}
```
`application.yml`:
```yaml
gateway:
  cors:
    allowed-origins: "http://localhost:5173"
```

- [ ] **Step 4: 통과 확인 + 커밋** — PASS 후 `git commit -am "feat(gateway): CORS 화이트리스트+credentials(CorsFilter)"`

---

### Task 8: gateway CSRF double-submit 필터 + 등록 순서

**Files:**
- Create: `api-gateway/src/main/java/com/example/gateway/filter/CsrfFilter.java`
- Create: `api-gateway/src/main/java/com/example/gateway/config/FilterConfig.java`
- Test: `api-gateway/src/test/java/com/example/gateway/filter/CsrfFilterTest.java`

**Interfaces:**
- Consumes: `CorsFilter`(Task 7).
- Produces: 상태변경 메서드에서 `csrf_token` 쿠키==`X-CSRF-Token` 헤더 검증. 불일치/누락 403(`{code:CSRF_TOKEN_INVALID}`). 예외: login/signup/refresh, 안전 메서드. 등록 순서 CORS→CSRF.

- [ ] **Step 1: 실패 테스트**

```java
package com.example.gateway.filter;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfFilterTest {
    private final CsrfFilter filter = new CsrfFilter();

    @Test
    void rejectsPostWithoutCsrf() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsPostWithMatchingCsrf() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/logout");
        req.setCookies(new Cookie("csrf_token", "abc"));
        req.addHeader("X-CSRF-Token", "abc");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // 통과(다음 필터 호출됨)
    }

    @Test
    void skipsPublicLogin() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/auth/login");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull(); // CSRF 없이 통과
    }

    @Test
    void skipsSafeGet() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/auth/me");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
```

- [ ] **Step 2: 실패 확인** — FAIL.

- [ ] **Step 3: 구현**

```java
package com.example.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** CSRF double-submit(쿠키==헤더). spring-security 미사용 — OncePerRequestFilter. */
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final Set<String> PUBLIC = Set.of("/v1/auth/login", "/v1/auth/signup", "/v1/auth/refresh");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (requiresCheck(req)) {
            String cookie = cookie(req, "csrf_token");
            String header = req.getHeader("X-CSRF-Token");
            if (cookie == null || header == null || !cookie.equals(header)) {
                res.setStatus(HttpStatus.FORBIDDEN.value());
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.getWriter().write("{\"code\":\"CSRF_TOKEN_INVALID\",\"message\":\"CSRF 검증 실패\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private boolean requiresCheck(HttpServletRequest req) {
        return !SAFE.contains(req.getMethod()) && !PUBLIC.contains(req.getRequestURI());
    }

    private String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
```
```java
// FilterConfig.java — 등록 순서 CORS(최우선) → CSRF
package com.example.gateway.config;

import com.example.gateway.filter.CsrfFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class FilterConfig {
    @Bean
    FilterRegistrationBean<CorsFilter> corsRegistration(CorsFilter corsFilter) {
        var reg = new FilterRegistrationBean<>(corsFilter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
    @Bean
    FilterRegistrationBean<CsrfFilter> csrfRegistration() {
        var reg = new FilterRegistrationBean<>(new CsrfFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);  // CORS 다음
        return reg;
    }
}
```
> 주의: Task 7의 `@Bean CorsFilter`는 유지하되, 자동등록 중복을 피하려면 `FilterRegistrationBean`으로만 체인에 올린다(스프링부트가 Filter 타입 빈을 자동등록하므로 순서 명시를 위해 Registration 사용).

- [ ] **Step 4: 통과 확인 + 커밋** — `./gradlew :api-gateway:test --tests "*CsrfFilterTest"` → PASS. `git add -A && git commit -m "feat(gateway): CSRF double-submit 필터 + CORS/CSRF 순서"`

---

### Task 9: gateway 라우트에 /v1/auth/me 추가

**Files:**
- Modify: `api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java`
- Test: `api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java` (기존에 케이스 추가)

**Interfaces:**
- Produces: `GET /v1/auth/me`가 secured 라우트로 user downstream에 전달(JwtTrustHeaderFilter 부착).

- [ ] **Step 1: 라우트 추가** — `userAuthSecuredRoute`의 path에 `/v1/auth/me` 포함

```java
return route("user-auth-secured")
        .route(path("/v1/auth/logout").or(path("/v1/auth/me")), http())
        .before(uri(userUri))
        .filter(jwt)
        .build();
```

- [ ] **Step 2: 통합 테스트 케이스 추가** (기존 GatewayRoutingIT 스타일 따름 — 쿠키 없는 /me는 401)

```java
@Test
void me_withoutToken_returns401() {
    // 기존 IT의 TestRestTemplate/포트 셋업 재사용
    ResponseEntity<String> resp = restTemplate.getForEntity(url("/v1/auth/me"), String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
}
```

- [ ] **Step 3: 통과 확인 + 커밋** — `./gradlew :api-gateway:test` → PASS. `git commit -am "feat(gateway): /v1/auth/me secured 라우트"`

---

### Task 10: 프론트엔드 스캐폴드 (Vite+React, 프록시 없음, CSP)

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.js`, `frontend/index.html`, `frontend/src/main.jsx`

**Interfaces:**
- Produces: `npm run dev`로 `http://localhost:5173`에서 뜨는 빈 React 앱. dev proxy 없음. CSP meta 포함.

- [ ] **Step 1: 스캐폴드 생성**

```bash
cd /Users/juho/Documents/docswithmulti
npm create vite@latest frontend -- --template react
cd frontend && npm install
```

- [ ] **Step 2: vite.config.js — proxy 없음(실 cross-origin 검증), 포트 고정**

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true },  // dev proxy 의도적으로 없음 — CORS 정식 경로 검증
})
```

- [ ] **Step 3: index.html — CSP meta 추가**

```html
<!-- <head>에 추가: 인라인 스크립트 차단, self만 허용. connect-src에 게이트웨이 출처 명시 -->
<meta http-equiv="Content-Security-Policy"
      content="default-src 'self'; connect-src 'self' http://localhost:8000; style-src 'self' 'unsafe-inline'">
```
> Vite dev는 HMR로 인라인/ws를 쓰므로 dev에서는 CSP가 일부 완화될 수 있음 — 프로덕션 빌드(`npm run build`) 산출물 기준으로 CSP를 최종 검증하고, 필요한 최소 지시자만 추가한다.

- [ ] **Step 4: 실행 확인 + 커밋**

```bash
npm run dev   # localhost:5173 뜨는지 확인 후 종료
cd .. && git add frontend && git commit -m "chore(frontend): Vite+React 스캐폴드(프록시 없음, CSP)"
```

---

### Task 11: 로그인 데모 UI + API 클라이언트 + 수동 검증

**Files:**
- Create: `frontend/src/api.js`
- Modify: `frontend/src/App.jsx`

**Interfaces:**
- Consumes: 백엔드 `/v1/auth/{signup,login,logout,me}` (게이트웨이 :8000 경유).
- Produces: 로그인/회원가입 토글 폼 → 성공 시 `/me` 표시 → 로그아웃(CSRF 헤더 첨부).

- [ ] **Step 1: api.js — credentials 포함, CSRF 헤더 helper, 토큰 미접근**

```js
const BASE = 'http://localhost:8000'  // 게이트웨이. 실 cross-origin.

function csrfToken() {
  return document.cookie.split('; ').find(c => c.startsWith('csrf_token='))?.split('=')[1]
}

async function req(path, { method = 'GET', body, csrf = false } = {}) {
  const headers = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (csrf) headers['X-CSRF-Token'] = csrfToken() ?? ''
  const res = await fetch(BASE + path, {
    method, headers,
    credentials: 'include',                 // 쿠키 송수신 (httpOnly 토큰은 JS가 못 봄)
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.message || data.code || `HTTP ${res.status}`)
  return data
}

export const api = {
  signup: (b) => req('/v1/auth/signup', { method: 'POST', body: b }),
  login:  (b) => req('/v1/auth/login',  { method: 'POST', body: b }),
  me:     ()  => req('/v1/auth/me'),
  logout: ()  => req('/v1/auth/logout', { method: 'POST', csrf: true }),
}
```

- [ ] **Step 2: App.jsx — 로그인/회원가입 토글 + /me 표시 + 로그아웃**

```jsx
import { useState } from 'react'
import { api } from './api'

export default function App() {
  const [mode, setMode] = useState('login')       // 'login' | 'signup'
  const [form, setForm] = useState({ email: '', password: '', name: '', phone: '' })
  const [me, setMe] = useState(null)
  const [err, setErr] = useState('')

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault(); setErr('')
    try {
      if (mode === 'signup') await api.signup(form)
      else await api.login({ email: form.email, password: form.password })
      setMe(await api.me())                         // 신원은 /me로만 (토큰 미접근)
    } catch (e) { setErr(e.message) }
  }

  async function logout() {
    try { await api.logout(); setMe(null) } catch (e) { setErr(e.message) }
  }

  if (me) return (
    <main style={{ fontFamily: 'sans-serif', padding: 40 }}>
      <h1>안녕하세요, {me.name}님</h1>
      <p>{me.email} · {me.role} · userId {me.userId}</p>
      <button onClick={logout}>로그아웃</button>
    </main>
  )

  return (
    <main style={{ fontFamily: 'sans-serif', padding: 40, maxWidth: 320 }}>
      <h1>{mode === 'login' ? '로그인' : '회원가입'}</h1>
      <form onSubmit={submit} style={{ display: 'grid', gap: 8 }}>
        <input placeholder="email" value={form.email} onChange={set('email')} />
        <input placeholder="password" type="password" value={form.password} onChange={set('password')} />
        {mode === 'signup' && <input placeholder="name" value={form.name} onChange={set('name')} />}
        {mode === 'signup' && <input placeholder="phone" value={form.phone} onChange={set('phone')} />}
        <button type="submit">{mode === 'login' ? '로그인' : '가입'}</button>
      </form>
      {err && <p style={{ color: 'crimson' }}>{err}</p>}
      <button onClick={() => setMode(mode === 'login' ? 'signup' : 'login')} style={{ marginTop: 12 }}>
        {mode === 'login' ? '회원가입으로' : '로그인으로'}
      </button>
    </main>
  )
}
```

- [ ] **Step 3: 수동 검증 (E2E는 스코프 밖 — 체크리스트)**

백엔드 2개 기동: `./gradlew :user-service:bootRun` + `./gradlew :api-gateway:bootRun` (+ user-service MySQL). 프론트 `npm run dev`.

1. [ ] 회원가입 → "안녕하세요 X님" 표시 (Network: login 응답에 `Set-Cookie: access_token=...; HttpOnly` 확인).
2. [ ] DevTools Application→Cookies: `access_token`/`refresh_token`은 HttpOnly=✓, `csrf_token`은 HttpOnly=✗.
3. [ ] Console에서 `document.cookie` — access/refresh 토큰이 **안 보임**(httpOnly), csrf만 보임.
4. [ ] 로그아웃 → Network에 `X-CSRF-Token` 헤더 존재, 200, 쿠키 만료(Max-Age=0).
5. [ ] CSRF 음성 확인: DevTools에서 csrf 헤더 없이 logout POST 재현 시 403.
6. [ ] CORS 확인: 다른 출처(예: `http://127.0.0.1:5500`)에서 호출 시 브라우저가 차단(화이트리스트에 없음).

- [ ] **Step 4: 커밋** — `git add frontend && git commit -m "feat(frontend): 로그인/회원가입/로그아웃 데모 UI + /me 연동"`

---

## Self-Review

**Spec 커버리지:**
- httpOnly 쿠키 발급 → Task 1,2 · refresh 쿠키화 → Task 3 · /me → Task 4 · 쿠키 인증(gateway/user) → Task 5,6 · CORS → Task 7 · CSRF → Task 8 · 라우트 → Task 9 · 프론트(토큰 미접근, CSP, 프록시 없음) → Task 10,11. OWASP 표 A01~A07/CSRF 모두 태스크에 매핑됨.

**Placeholder 스캔:** 모든 코드 스텝에 실제 코드 포함. `User.reconstruct`(Task 4)와 GatewayRoutingIT 셋업(Task 9)은 "실제 정의를 확인해 맞추라"고 명시된 유일한 조정 지점 — 구현자가 해당 파일을 읽어 시그니처를 맞춘다.

**타입 일관성:** 쿠키명 상수(`access_token`/`refresh_token`/`csrf_token`)는 Task 1 `AuthCookieFactory` 정의와 Task 5/6/8/11의 문자열 리터럴이 일치. `MeResponse(userId,email,name,role)`는 Task 4에서 정의 후 Task 11 UI가 동일 필드 사용. `X-CSRF-Token` 헤더명은 Task 7(허용헤더)·8(검증)·11(전송) 일치.

## 알려진 조정 지점 (구현 중 실제 파일 확인 필요)

- `User.reconstruct(...)` 인자 순서/개수 — `domain/entity/User.java` 확인 후 Task 4 테스트 조정.
- user-service의 `AuthCookieFactory`/`UserQueryUseCase` 빈 등록 위치 — 기존 `@Configuration` 클래스 관례 따름.
- GatewayRoutingIT의 포트/`TestRestTemplate` 셋업 — 기존 파일 스타일 재사용.
- CSP 지시자 — 프로덕션 빌드 기준 최소 지시자로 최종 확정(dev HMR은 완화 가능).
