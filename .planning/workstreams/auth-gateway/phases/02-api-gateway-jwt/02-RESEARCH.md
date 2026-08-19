# Phase 2: API Gateway (JWT 검증 + 라우팅) - Research

**Researched:** 2026-07-30
**Domain:** API Gateway (단일 진입점 라우팅) · 게이트웨이 JWT 검증 · 신뢰 헤더 전달 · 401 차단
**Confidence:** HIGH (Boot4/Spring Cloud 호환 실측 · 라우팅/필터 API 실측 · JWT 계약 소스코드 실측)

---

## ⭐ COMPATIBILITY VERDICT (최상단 — 이 phase의 실현 가능성)

**결론: VIABLE. Spring Boot 4.0.x는 Spring Cloud 2025.1.x(Oakwood) / Spring Cloud Gateway 5.0.x로 완전 지원된다.** RESEARCH BLOCKED 아님.

- Spring Cloud **2025.1.0 "Oakwood"** (2025-11-25 GA)가 Spring Framework 7 + **Spring Boot 4** 기반으로 출시됨. 각 서브프로젝트가 5.0.0으로 메이저 승격. [VERIFIED: spring.io blog 2025/11/25]
- 이후 2025.1.1(2026-01), **2025.1.2(2026-06, Boot 4.0.7 대응 + 4.1.0 compat)** 패치 릴리스 존재. [VERIFIED: spring.io blog 2026/06/11]
- Spring Cloud Gateway 5.0.x가 Boot 4 라인. 아티팩트가 **개명**됨 — 서블릿(MVC)과 리액티브(WebFlux)로 명시 분리. [VERIFIED: spring.io blog 2025/11/25]

**선택: 리액티브(Netty) 아님 → 서블릿(MVC) 게이트웨이.** 이유는 이 프로젝트 구조가 강제한다:

> 루트 `build.gradle`의 `subprojects { dependencies { implementation 'org.springframework.boot:spring-boot-starter-web' } }`가 **모든 모듈**(신규 gateway 포함)에 서블릿 웹(Tomcat)을 강제한다. [VERIFIED: git show main:build.gradle]

- 리액티브 게이트웨이(`spring-cloud-starter-gateway-server-webflux`)는 WebFlux가 필요한데, classpath에 `spring-boot-starter-web`(서블릿)이 이미 있으면 Boot이 앱 타입을 SERVLET으로 결정 → 리액티브 게이트웨이가 정상 기동 안 됨. 쓰려면 루트가 주입한 starter-web을 gateway 모듈에서 배제해야 함(루트 subprojects 블록과 싸움 — 취약).
- **MVC 게이트웨이(`spring-cloud-starter-gateway-server-webmvc`)는 서블릿 기반 → 강제된 starter-web과 네이티브 호환.** 싸움 0.
- 스코프(단순 라우팅 + JWT 필터 + 헤더 주입 + 401)에 리액티브 backpressure 이점 불필요. Java 21 가상스레드가 블로킹 I/O 동시성 커버.

**정확한 의존성 좌표 (gateway 모듈 build.gradle):**
```gradle
// io.spring.dependency-management는 루트 subprojects가 이미 적용함 → BOM import만 추가
dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:2025.1.2"
    }
}
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc'  // 버전 BOM 관리(→ 5.0.x)
    // JWT 검증(verify-only). user-service와 동일 jjwt 라인
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'
    // 통합테스트: downstream 스텁
    testImplementation 'org.wiremock:wiremock-standalone:3.9.2'
}
```
- **spring-boot-starter-security 추가 금지.** 게이트웨이 JWT 게이트는 Security 필터체인이 아니라 게이트웨이 `HandlerFilterFunction`으로 구현 → Security 7 설정 부담 회피. 루트 subprojects는 security를 주입하지 않으므로 요청 안 하면 안 붙음. [VERIFIED: git show main:build.gradle]

**⚠ 유일한 잔여 리스크(calibration knob):** BOM `2025.1.2`는 Boot 4.0.7 기준 테스트, 프로젝트는 Boot **4.0.5** 핀. `io.spring.dependency-management`에서 Boot 자체 BOM(boot 플러그인)이 Boot 관리 아티팩트에 우선하고 spring-cloud BOM은 cloud 아티팩트만 공급하므로, 패치 갭(4.0.5↔4.0.7)은 stable minor 내 저위험. **Phase gate에서 `./gradlew :api-gateway:build`로 확정.** 실패 시 knob 2택: (a) 루트 Boot을 4.0.7로 상향(취소코어 4서비스 재빌드·검증 필요 — 비권장), (b) spring-cloud BOM을 4.0.5에 맞는 2025.1.x로 하향. [ASSUMED: 패치 갭 저위험 — 빌드로 검증 필요]

---

<user_constraints>
## User Constraints (from phase goal + REQUIREMENTS.md — CONTEXT.md 부재)

### Locked Decisions
- 게이트웨이 집약 JWT 검증 + 검증된 신원(userId·role) 신뢰 헤더 전달. **downstream 재검증 없음.**
- 게이트웨이는 **무상태(자체 DB 없음)**. HTTP 라우팅만. 모듈 간 DB 직접 접근 금지.
- JWT 시크릿 하드코딩 금지 — env(`JWT_SECRET`) 주입, **user-service와 동일 시크릿 공유**(HS256).
- 클라이언트가 보낸 `X-User-*` 헤더는 게이트웨이에서 **반드시 strip 후 재설정**(스푸핑 방지, 비협상).
- 취소 코어 4개 서비스 로직 불변. 게이트웨이는 앞단에 얹힘. downstream 인가(payment role)는 **Phase 3**.
- 스택 고정: Java 21 · Spring Boot 4.0.5 · Gradle 멀티모듈.

### Claude's Discretion
- 게이트웨이 신규 포트(권장: **8000**).
- JWT 검증 로직 재사용 방식(공유 모듈 vs 게이트웨이 자체 구현) — 아래 §Common 모듈 참조.
- 어떤 downstream 경로를 게이트웨이에 노출할지(client-facing vs 내부 service-to-service).
- 401 에러 응답 코드/문구.

### Deferred Ideas (OUT OF SCOPE)
- rate-limiting / circuit breaker (인증 경계 안정화 후).
- service discovery / config server (진입점 하나로 충분 — YAGNI).
- 외부 IdP / OAuth2 소셜 로그인.
- 분산추적(OTEL/Tempo) 상시화.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| GATE-01 | 단일 게이트웨이 진입점 → downstream 라우팅 | Spring Cloud Gateway MVC RouterFunction 라우트(`GatewayRouterFunctions.route().route(path(...), http(uri))`). §Architecture 라우팅 테이블 |
| GATE-02 | JWT 검증 + userId·role 신뢰 헤더 전달 | `HandlerFilterFunction` before-filter: 검증 성공 시 `X-User-Id`/`X-User-Role`(옵션 `X-Merchant-Id`) 세팅. JWT 계약은 user-service `JwtTokenProvider` 실측(subject=userId, claim `role`, opt `merchantId`) |
| GATE-03 | 무효/만료/누락 토큰 → downstream 도달 전 401 | 동일 before-filter가 `next.handle()` 호출 없이 `ServerResponse.status(401)` 단락 반환. 공개 경로(signup/login/refresh)는 필터 미적용 |
</phase_requirements>

## Summary

이 phase는 **신규 무상태 모듈(api-gateway) 1개 추가**다. 최상단 VERDICT대로 Spring Boot 4.0.5는 Spring Cloud 2025.1.2 / **Gateway 5.0.x(서블릿 MVC 변형)**로 지원된다. 리액티브가 아니라 MVC 게이트웨이를 쓰는 이유는 성능 취향이 아니라 **루트 `subprojects{}` 블록이 모든 모듈에 서블릿 `spring-boot-starter-web`을 강제**하기 때문 — MVC 게이트웨이는 그것과 네이티브 호환, 리액티브는 배제 싸움을 요구한다.

게이트웨이의 3책임(라우팅/검증/차단)은 전부 gateway-webmvc의 함수형 API 하나로 응집된다: 라우트는 `RouterFunction`, JWT 검증·헤더주입·401은 라우트에 붙는 단일 `HandlerFilterFunction` before-filter. Spring Security를 붙이지 않으므로 Security 7 설정 부담이 없다.

JWT 계약은 user-service `JwtTokenProvider`(실측)에서 고정된다: **HS256, `subject`=userId, claim `role`=역할명, 옵션 claim `merchantId`.** 게이트웨이는 이 토큰의 **verify 절반만** 필요(서명검증 + subject/role 추출) — `UserRole` enum도 불필요(role은 문자열 claim → 문자열 헤더). 따라서 공유 모듈을 새로 만드는 것보다 **verify-only 파싱 ~15줄을 게이트웨이에 자체 구현**하는 것이 더 작은 diff이자 모듈 격리에 부합한다. 동기화 대상은 코드가 아니라 **계약(시크릿 + HS256 + claim 스키마)**이다.

**Primary recommendation:** `spring-cloud-starter-gateway-server-webmvc`(BOM 2025.1.2) + jjwt 0.12.6로 신규 `api-gateway` 모듈(포트 8000)을 만든다. 라우트는 yaml 또는 RouterFunction, JWT 게이트는 라우트에 붙는 단일 before-filter(strip → verify → 401 or 헤더주입). Spring Security 미사용. `JWT_SECRET`은 user-service와 동일 값 env 주입. 통합테스트는 WireMock으로 downstream 스텁 + `@SpringBootTest(RANDOM_PORT)`.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 클라이언트 단일 진입점 라우팅(GATE-01) | Frontend/Edge Gateway (api-gateway) | — | 신규 무상태 edge 모듈. 서비스 discovery 없이 정적 라우트 |
| JWT 서명 검증 + 클레임 추출(GATE-02) | Gateway filter (api-gateway) | — | 집약 검증. downstream 재검증 없음(locked) |
| 신뢰 헤더 주입(X-User-Id/Role) | Gateway filter (api-gateway) | Phase 3 payment(소비) | 게이트웨이가 신원 truth 생성, downstream은 신뢰 소비 |
| 클라 스푸핑 헤더 strip | Gateway filter (api-gateway) | — | 인가 우회 방지. **모든 라우트에 무조건 적용** |
| 미인증 401 차단(GATE-03) | Gateway filter (api-gateway) | — | before-filter 단락. downstream 도달 전 |
| 토큰 발급/갱신/무효화 | user-service (Phase 1, 불변) | — | 게이트웨이는 발급 안 함. verify만 |
| role 기반 취소 인가 | payment-service (Phase 3) | — | 이 phase 범위 밖 |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-cloud-starter-gateway-server-webmvc | 5.0.x (BOM 관리) | 서블릿 기반 게이트웨이(라우팅+필터) | Boot 4 라인 공식 게이트웨이, 루트가 강제한 서블릿 web과 호환 [VERIFIED: central.sonatype.com spring-cloud-starter-gateway-server-webmvc/5.0.0] |
| spring-cloud-dependencies (BOM) | 2025.1.2 | Spring Cloud 버전 관리 | Oakwood 릴리스 트레인, Boot 4 대응 [VERIFIED: spring.io blog] |
| io.jsonwebtoken:jjwt-api | 0.12.6 (compile) | JWT 파서 API(verify) | user-service와 동일 라인 → 계약 일치. Spring 독립 [CITED: Phase 1 RESEARCH, mvnrepository] |
| io.jsonwebtoken:jjwt-impl | 0.12.6 (runtimeOnly) | jjwt 구현 | 3분할 모듈화 |
| io.jsonwebtoken:jjwt-jackson | 0.12.6 (runtimeOnly) | JSON 역직렬화 | 위와 동일 |

### Supporting (루트 subprojects가 이미 제공 — gateway build.gradle 재선언 금지)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-boot-starter-web | Boot 4.0.5 | 서블릿 웹(Tomcat) | 이미 공통. **MVC 게이트웨이의 전제** [VERIFIED: main root build.gradle] |
| spring-boot-starter-actuator + micrometer-prometheus | Boot 4.0.5 | health/metrics | 이미 공통. 게이트웨이 health endpoint 무료 |
| spring-boot-starter-test | Boot 4.0.5 | 통합테스트 골격 | 이미 공통 |

### 재선언 금지 목록 (루트 강제)
- `spring-boot-starter-web`, `actuator`, `validation`, `data-jpa`, `flyway`, `mysql-connector-j`, `lombok`, `testcontainers`, `archunit` — 전부 subprojects 공통.
- **주의:** 게이트웨이는 무상태이나 루트가 `spring-boot-starter-data-jpa` + `mysql-connector-j`를 강제 주입한다. DataSource 미설정 시 기동 실패 → gateway `application.yml`에 `spring.autoconfigure.exclude`로 `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration` 제외 필요(무상태 모듈이 강제 JPA를 무력화하는 표준 패턴). [ASSUMED: 루트 JPA 강제 → 제외 필요. 빌드/기동으로 확인]

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| MVC 게이트웨이(webmvc) | 리액티브 게이트웨이(webflux) | 루트가 서블릿 web 강제 → webflux는 배제 싸움 필요. 스코프에 리액티브 이점 없음. **MVC가 정답** |
| gateway 자체 JWT verify | Spring Security resource-server(JWT) | Security 7 필터체인 설정 부담 + HS256 대칭키 resource-server 구성 번거로움. 단순 before-filter가 더 작음 |
| verify 로직 게이트웨이 복제 | 공유 common-auth 모듈 | 소비자 사실상 1곳 + role은 String이라 UserRole enum 불요 → 공유 모듈은 과설계(YAGNI). §Common 모듈 |
| 정적 라우트 yaml | service discovery(Eureka) | REQUIREMENTS out-of-scope. 5개 고정 서비스엔 정적 라우트로 충분 |

## Package Legitimacy Audit

> 생태계는 **Maven** (npm/pypi/crates seam 비대상). 수동 검증.

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| org.springframework.cloud:spring-cloud-starter-gateway-server-webmvc 5.0.0 | Maven Central | 공식 스프링(2025.11 GA) | github.com/spring-cloud/spring-cloud-gateway | OK | Approved [VERIFIED: central.sonatype.com] |
| org.springframework.cloud:spring-cloud-dependencies 2025.1.2 | Maven Central | 공식 스프링 | github.com/spring-cloud/spring-cloud-release | OK | Approved [VERIFIED: spring.io blog] |
| io.jsonwebtoken:jjwt-{api,impl,jackson} 0.12.6 | Maven Central | 수년(jwtk) | github.com/jwtk/jjwt | OK | Approved [CITED: Phase 1 검증 상속] |
| org.wiremock:wiremock-standalone 3.9.2 | Maven Central | 성숙(wiremock org) | github.com/wiremock/wiremock | OK | Approved [ASSUMED: 버전 핀 — 최신 3.x 빌드시 확인] |

**REMOVED:** 없음. **SUS:** 없음.

## Architecture Patterns

### System Architecture Diagram

```
[Client]
   │  (모든 요청, Authorization: Bearer <access JWT>)
   ▼
┌─────────────────────── api-gateway :8000 (무상태, 서블릿 MVC) ───────────────────────┐
│                                                                                        │
│  RouterFunction 라우트 매칭 (GATE-01)                                                  │
│     ├─ /v1/auth/signup|login|refresh  ─── [공개: 필터 미적용, strip만] ──┐             │
│     └─ 그 외 모든 라우트              ─── [JWT before-filter 적용] ───────┤             │
│                                                                          ▼             │
│   [JwtTrustHeaderFilter : HandlerFilterFunction before-filter]                         │
│     1. strip: 클라가 보낸 X-User-Id / X-User-Role / X-Merchant-Id 제거 (무조건)        │
│     2. Authorization 헤더에서 Bearer 추출                                               │
│        ├─ 없음/형식오류 ─────────────▶ ✗ 401 {code,message} 단락 (next 미호출) GATE-03 │
│     3. jjwt verifyWith(HS256 secret).parseSignedClaims                                  │
│        ├─ 서명불일치/만료/파싱실패 ──▶ ✗ 401 단락 GATE-03                               │
│     4. ✓ subject→X-User-Id, claim role→X-User-Role, (opt) merchantId→X-Merchant-Id 세팅│
│        └─ next.handle(mutatedRequest) ──▶ 라우트 uri로 프록시 (GATE-02)                 │
│                                                                                        │
└────────────────────────────────────────┬───────────────────────────────────────────┘
                                          │  (신뢰 헤더 부착된 요청)
        ┌──────────────┬──────────────────┼───────────────┬─────────────────┐
        ▼              ▼                  ▼               ▼                 ▼
  user-service    payment-service    order-service   merchant-limit    risk-mgmt
    :8085            :8080              :8081            :8082            :8083
 (auth/logout)   (/v1/payments      (Phase3 인가는   (내부 위주)       (내부 위주)
                  /{key}/cancel)     payment가 소비)

시크릿 공유: user-service(발급)와 api-gateway(검증)가 동일 JWT_SECRET env로 HS256 대칭키 공유.
downstream은 신뢰 헤더만 읽고 JWT 재검증 안 함 (locked).
```

### Recommended Project Structure
```
api-gateway/
├── build.gradle                        # BOM import + gateway-webmvc + jjwt (재선언 금지 준수)
└── src/main/
    ├── java/com/example/gateway/
    │   ├── ApiGatewayApplication.java
    │   ├── config/
    │   │   ├── RouteConfig.java         # RouterFunction<ServerResponse> 라우트 정의 (또는 yaml)
    │   │   └── JwtVerifier.java         # verify-only jjwt 래퍼 (~15줄, secret 주입)
    │   └── filter/
    │       └── JwtTrustHeaderFilter.java # HandlerFilterFunction: strip→verify→401/헤더주입
    └── resources/
        └── application.yml              # server.port 8000, jwt.secret ${JWT_SECRET},
                                         # downstream uri env-override, JPA/DataSource autoconfig 제외
(테스트) src/test/java/.../GatewayRoutingIT.java  # WireMock 스텁 + RANDOM_PORT
```

### Pattern 1: gateway-webmvc RouterFunction 라우트 (GATE-01)
**What:** 함수형 라우트. path 매칭 → downstream uri로 http 프록시. 인증 라우트엔 before-filter 부착.
**When:** 정적 5-서비스 라우팅. discovery 불요.
```java
// Source: docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc [CITED]
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

@Bean
RouterFunction<ServerResponse> gatewayRoutes(JwtTrustHeaderFilter jwt) {
    return route("payment")
            .route(path("/v1/payments/**"), http("http://localhost:8080"))
            .filter(jwt)                         // 인증 필요
        .and(route("user-auth-public")
            .route(path("/v1/auth/signup", "/v1/auth/login", "/v1/auth/refresh"),
                   http("http://localhost:8085")))   // 공개: JWT 필터 없음, strip만 별도 적용
        .and(route("user-auth-secured")
            .route(path("/v1/auth/logout"), http("http://localhost:8085"))
            .filter(jwt));
    // order/merchant/risk 라우트도 동일 패턴 + jwt 필터
}
```
**대안:** 동일 라우트를 `application.yml`의 `spring.cloud.gateway.server.webmvc.routes[]`로 선언 가능 — 필터가 표준 필터(strip/add-header)뿐이면 yaml이 더 짧다. 단 **커스텀 JWT verify 401 단락**은 커스텀 `HandlerFilterFunction`을 요구하므로 RouterFunction 방식이 응집적. [ASSUMED: yaml 표준필터로 strip+add는 되나 verify/401 단락은 커스텀 필터 필요]

### Pattern 2: 단일 before-filter — strip + verify + 401/헤더주입 (GATE-02/03)
**What:** 한 필터에 3책임 응집. 서블릿 MVC이므로 `HandlerFilterFunction<ServerResponse,ServerResponse>`.
```java
// Source: 패턴 도출(gateway-webmvc HandlerFilterFunction) + user-service JwtTokenProvider 계약 [CITED/VERIFIED]
public ServerResponse filter(ServerRequest req, HandlerFunction<ServerResponse> next) throws Exception {
    // 1. strip 클라 스푸핑 (무조건) — from()으로 복사 후 문제 헤더 미포함 재빌드
    var b = ServerRequest.from(req)
            .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Role"); h.remove("X-Merchant-Id"); });
    // 2+3. Bearer 추출 + 검증
    String auth = req.headers().firstHeader(HttpHeaders.AUTHORIZATION);
    if (auth == null || !auth.startsWith("Bearer ")) return unauthorized("TOKEN_MISSING");
    Claims c;
    try { c = verifier.parse(auth.substring(7)); }        // jjwt verifyWith(secret).parseSignedClaims
    catch (ExpiredJwtException e) { return unauthorized("TOKEN_EXPIRED"); }
    catch (JwtException | IllegalArgumentException e) { return unauthorized("TOKEN_INVALID"); }
    // 4. 신뢰 헤더 주입 후 프록시
    b.header("X-User-Id", c.getSubject());
    b.header("X-User-Role", c.get("role", String.class));
    Object m = c.get("merchantId"); if (m != null) b.header("X-Merchant-Id", String.valueOf(m));
    return next.handle(b.build());
}
private ServerResponse unauthorized(String code) {
    return ServerResponse.status(401).contentType(APPLICATION_JSON)
            .body(Map.of("code", code, "message", "인증 실패"));   // user-service 에러 envelope와 동일 형태
}
```
**Note (calibration knob):** `ServerRequest.from(req).headers(remove...)`로 strip이 되는지는 gateway-webmvc 5.0 API 세부에 의존. 만약 `from()`이 헤더 제거를 지원 안 하면 대안은 표준 필터 `BeforeFilterFunctions.removeRequestHeader("X-User-Id")`를 라우트에 체인 + 커스텀 필터는 verify/add만 담당. [ASSUMED: from().headers(remove) 동작 — 빌드 시 API 확인]

### Anti-Patterns to Avoid
- **downstream에서 JWT 재검증:** locked 설계 위반. downstream은 X-User-* 헤더만 신뢰(Phase 3).
- **클라 X-User-* strip 누락:** 스푸핑 → 인가 우회. **모든 라우트(공개 포함)에서 무조건 strip.** 공개 라우트도 strip 필요(downstream이 실수로 읽을 여지 차단).
- **Spring Security 필터체인 도입:** HS256 대칭키 게이트웨이엔 과함. before-filter로 충분.
- **게이트웨이에 DataSource/JPA 배선:** 무상태. 루트 강제 JPA는 autoconfig 제외로 무력화.
- **리액티브 게이트웨이 억지 사용:** 루트 서블릿 강제와 충돌.
- **JWT_SECRET 게이트웨이/유저서비스 값 불일치:** HS256 대칭키 → 값 다르면 전체 401. 동일 env 값 주입 필수.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 리버스 프록시/라우팅 | 수동 RestTemplate 포워딩 컨트롤러 | gateway-webmvc `http(uri)` 핸들러 | 헤더/바디/스트리밍/타임아웃 전파 |
| JWT 서명검증 | 수동 HMAC + base64url 파싱 | jjwt `parser().verifyWith().parseSignedClaims` | 서명·만료·클레임 엣지케이스 (user-service와 동일 라이브러리) |
| 헤더 strip/set | raw HttpServletRequest 조작 | `ServerRequest.from()` 또는 `BeforeFilterFunctions.removeRequestHeader/addRequestHeader` | 서블릿 헤더 불변성·대소문자 처리 |
| downstream 스텁(테스트) | 실제 5개 서비스 기동 | WireMock | 격리·빠름·계약 검증 |

**Key insight:** 게이트웨이 3책임(라우팅/검증/헤더)은 전부 성숙한 라이브러리 원시요소로 커버됨. 자체 구현은 verify 파싱 ~15줄뿐이고, 그것도 jjwt가 무거운 일을 함.

## Runtime State Inventory

> 신규 무상태 모듈(greenfield). 기존 런타임 상태 rename/migrate 없음. 5개 카테고리 전부 "해당 없음".

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — 게이트웨이 무상태, DB 없음 | 없음 |
| Live service config | None — 신규 모듈. 기존 4서비스 + user-service 설정 불변 | 없음 |
| OS-registered state | None | 없음 |
| Secrets/env vars | `JWT_SECRET`(신규 키 아님 — user-service와 **동일 값** 공유). 신규 downstream URI env(옵션) | 게이트웨이 배포 환경에 user-service와 동일 JWT_SECRET 주입 |
| Build artifacts | None — 신규 모듈 | settings.gradle `include 'api-gateway'` 후 최초 빌드 |

## Common Pitfalls

### Pitfall 1: Boot 4.0.5 ↔ Spring Cloud BOM 패치 갭
**What:** BOM 2025.1.2는 Boot 4.0.7 기준. 프로젝트는 4.0.5 핀. 드물게 아티팩트 버전 불일치 경고/충돌.
**Why:** 릴리스 트레인이 특정 Boot 패치를 타깃.
**How to avoid:** Boot BOM 우선 원칙 신뢰 + `./gradlew :api-gateway:build` phase gate 검증. 실패 시 VERDICT의 knob (a)/(b).
**Warning signs:** dependency resolution 시 Boot 아티팩트가 예상외 버전으로 끌려옴.

### Pitfall 2: 루트 강제 JPA로 게이트웨이 기동 실패
**What:** 무상태 게이트웨이에 루트가 `data-jpa` + `mysql-connector-j` 주입 → DataSource URL 없이 기동 실패.
**How to avoid:** `application.yml`에 `spring.autoconfigure.exclude: [org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration, org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration]`.
**Warning signs:** 기동 시 "Failed to configure a DataSource" 오류.

### Pitfall 3: 리액티브/서블릿 게이트웨이 혼동
**What:** `spring-cloud-starter-gateway-server-webflux`(리액티브) 선택 시 서블릿 web과 충돌로 게이트웨이 미동작.
**How to avoid:** **반드시 `-webmvc` 변형**. 아티팩트명 정확히 확인.
**Warning signs:** 라우트가 404이거나 게이트웨이 필터가 안 걸림.

### Pitfall 4: JWT_SECRET 불일치 → 전면 401
**What:** 게이트웨이와 user-service에 다른 시크릿 주입 → 모든 유효 토큰이 검증 실패.
**How to avoid:** 동일 env 값. HS256 대칭키. 로컬은 user-service `application.yml` local 프로파일의 dev 기본값과 동일 값 사용.
**Warning signs:** 로그인은 되는데 이후 모든 요청 401.

### Pitfall 5: 공개 경로 strip 누락 / logout 오분류
**What:** signup/login/refresh를 인증 필터로 감싸면 토큰 없는 정상 요청이 401. 반대로 logout을 공개로 두면 미인증 로그아웃.
**How to avoid:** 공개 = signup/login/refresh **정확히 3개**. logout은 인증(Phase 1 SecurityConfig 실측과 일치). strip은 공개 경로에도 적용.
**Warning signs:** 회원가입이 401 / logout이 토큰 없이 통과.

### Pitfall 6: Boot 4 테스트 스캐폴딩 제약
**What:** Phase 1에서 `TestRestTemplate`/`@AutoConfigureMockMvc` 일부 미제공 확인. 게이트웨이 통합테스트에서 동일 제약.
**How to avoid:** `@SpringBootTest(webEnvironment=RANDOM_PORT)` + JDK `HttpClient`(또는 `RestClient`)로 실포트 호출. downstream은 WireMock 스텁. §Validation.
**Warning signs:** 테스트 컴파일 시 미해결 심볼(TestRestTemplate).

## Code Examples

### verify-only JWT 래퍼 (게이트웨이 자체 구현, user-service 계약 미러)
```java
// Source: user-service JwtTokenProvider.validateToken/getClaims 실측 [VERIFIED: git show]
// 게이트웨이는 verify 절반만. create 로직·UserRole enum 불필요.
public class JwtVerifier {
    private final SecretKey key;
    public JwtVerifier(String secret) { this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
    public Claims parse(String token) {   // 만료/서명오류 시 jjwt 예외 그대로 던짐 → 필터가 401 분기
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

### JWT 계약 (동기화 대상 — 코드 아님, 계약임)
```
발급자: user-service JwtTokenProvider.createAccessToken  [VERIFIED: git show]
  - 알고리즘: HS256 (Keys.hmacShaKeyFor(secret UTF-8 bytes), 키 ≥256bit)
  - subject : String.valueOf(userId)          → 게이트웨이 X-User-Id
  - claim "role"      : UserRole.name()        → 게이트웨이 X-User-Role
  - claim "merchantId": Long (있을 때만)        → 게이트웨이 X-Merchant-Id (옵션)
  - issuedAt / expiration (access TTL 3,600,000ms = 1h)
검증자: api-gateway JwtVerifier (동일 secret, 동일 HS256)
```

### 401 에러 응답 (user-service GlobalExceptionHandler envelope와 동일)
```json
{ "code": "TOKEN_EXPIRED", "message": "인증 실패" }
```
```
게이트웨이 신규 코드(제안): TOKEN_MISSING / TOKEN_INVALID / TOKEN_EXPIRED → 전부 HTTP 401.
envelope 형태 {code,message}는 user-service GlobalExceptionHandler(Map.of("code","message")) 실측과 일치. [VERIFIED: git show]
error-catalog.md에 아직 인증 계열 코드 없음 → 신규 등록 대상(planner가 문서 갱신 태스크 포함).
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `spring-cloud-starter-gateway`(단일, 리액티브 암묵) | `-server-webmvc` / `-server-webflux` 명시 분리 | Spring Cloud 2025.1.0 (5.0) | 서블릿/리액티브 명시 선택. **이 프로젝트는 webmvc** [VERIFIED: spring.io blog] |
| Zuul 1 (블로킹, 유지보수 종료) | Spring Cloud Gateway 5.0 | 수년 전 | Zuul 사용 금지 |
| WebFlux 게이트웨이 강제 | 서블릿(webmvc) 게이트웨이 정식 지원 | 2022+(webmvc 도입), 5.0에서 아티팩트 명확화 | 서블릿 스택 프로젝트가 리액티브 강요 안 받음 |

**Deprecated/outdated:** 구 `spring-cloud-starter-gateway`(무접미사) 좌표는 5.0에서 개명 — `-server-webmvc` 사용. Netflix Zuul 사용 금지.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | BOM 2025.1.2(Boot 4.0.7) + 핀된 Boot 4.0.5 조합이 빌드 통과 | VERDICT | 중간 — 빌드로 확정. 실패 시 knob (a)Boot상향/(b)BOM하향 |
| A2 | 루트 강제 data-jpa로 인해 게이트웨이가 DataSource autoconfig 제외 필요 | Standard Stack / Pitfall 2 | 낮음 — 기동 오류 시 명확. exclude로 해결 |
| A3 | `ServerRequest.from(req).headers(remove)`로 strip 가능 | Pattern 2 | 중간 — API 세부. 대안: `BeforeFilterFunctions.removeRequestHeader` |
| A4 | yaml routes로 strip+add-header 표준필터는 되나 verify/401 단락은 커스텀 필터 필요 | Pattern 1 | 낮음 — 커스텀 필터가 기본 경로 |
| A5 | 신규 게이트웨이 포트 8000 (기존 8080~8085/3307~3315/6379/909x/8989와 무충돌) | Environment | 낮음 — 실측 포트맵 기준 |
| A6 | 공개 경로 = signup/login/refresh 3개, logout은 인증 | Routing | 낮음 — user-service SecurityConfig 실측과 일치 [VERIFIED] |
| A7 | order/merchant-limit/risk를 게이트웨이에 노출할지 = discretion | Routing | 낮음 — GATE-01 충족엔 라우트만 추가하면 됨. discuss에서 확정 |
| A8 | wiremock-standalone 3.9.2 버전 핀 | Package Audit | 낮음 — 최신 3.x로 조정 가능 |

## Open Questions (RESOLVED — planning 단계에서 잠금)

> Q1 → D-P2-5 (order/merchant/risk 내부 전용, 게이트웨이 미노출; user+payment만 라우팅).
> Q2 → tracer 빌드 게이트에서 확정(BOM patch를 4.0.5에 맞춤, 루트 Boot 안 올림).
> Q3 → 게이트웨이가 X-Merchant-Id를 비용 0으로 전달; Phase 3에서 소비 여부 결정.

1. **order/merchant-limit/risk 게이트웨이 노출 범위**
   - 아는 것: GATE-01은 "각 downstream 라우팅". 그러나 risk/merchant-limit은 payment가 HTTP로 호출하는 **내부 서비스**(client-facing 아님).
   - 불명확: 클라이언트가 order/risk/merchant를 직접 부르는가.
   - 권장: 라우트 추가는 저렴 → 5개 전부 라우트 정의하되 JWT 필터로 보호. 진짜 client-facing 여부는 discuss-phase에서 확정. 내부 전용이면 게이트웨이 미노출(서비스간 직접 HTTP 유지)이 더 깔끔.

2. **BOM ↔ Boot 정확 패치 매핑**
   - 아는 것: 2025.1.2 = Boot 4.0.7 타깃. 프로젝트 Boot 4.0.5.
   - 권장: BOM import + Boot 핀 유지로 빌드 검증. Tracer plan(모듈 스캐폴드 + 빌드)에서 최우선 확인.

3. **X-Merchant-Id 전달 필요 여부(Phase 3 선행)**
   - 아는 것: 토큰에 merchantId claim 있음(옵션). Phase 3 payment 인가가 role만 쓰는지 merchantId도 쓰는지.
   - 권장: 게이트웨이가 있으면 함께 전달(비용 0). Phase 3에서 소비 여부 결정.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 | 빌드/런타임 | ✓ (프로젝트 고정) | 21 | 없음 |
| Gradle 8 + boot 4.0.5 플러그인 | 빌드 | ✓ (루트 고정) | 8 / 4.0.5 | 없음 |
| Maven Central (spring-cloud 2025.1.2, gateway-webmvc 5.0) | 게이트웨이 | ✓ (공개 아티팩트) | 5.0.x | 없음 — BLOCKED if 미해결(하지만 존재 확인됨) |
| user-service :8085 (JWT 발급자) | 검증할 토큰 존재 | ✓ (Phase 1 완료) | — | 없음 — 발급자 필수 |
| downstream services (8080~8083) | 라우팅 타깃 | ✓ (기존 서비스) | — | 테스트는 WireMock 스텁 |
| WireMock | 통합테스트 downstream 스텁 | testImplementation 추가 필요 | 3.9.2 | 실서비스 기동(느림, 비권장) |
| Port 8000 | 게이트웨이 리스닝 | ✓ 미사용 | — | 8888 대체 |

**Blocking (fallback 없음):** 없음 — 모든 필수 의존성 존재 확인. (Spring Cloud 2025.1.2 / gateway-webmvc 5.0은 Maven Central 존재 실측.)

## Validation Architecture

> nyquist_validation 명시적 false 아님 → 포함.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + `spring-boot-starter-test`(루트 공통) + WireMock(downstream 스텁). 게이트웨이는 무상태 → Testcontainers 불요 |
| Config file | 없음 — `test { useJUnitPlatform() }` (루트 build.gradle) |
| Quick run | `./gradlew :api-gateway:test` |
| Full suite | `./gradlew :api-gateway:test` |
| Phase gate | `./gradlew :api-gateway:build` (Boot4 + Spring Cloud BOM 컴파일·기동 확정) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| GATE-01 | 게이트웨이 진입점 → 올바른 downstream 라우팅 | integration | `:api-gateway:test --tests '*GatewayRoutingIT'` (WireMock 스텁이 각 경로별 수신 확인) | ❌ Wave 0 |
| GATE-02 | 유효 JWT → downstream이 X-User-Id/X-User-Role 수신 | integration | `--tests '*GatewayRoutingIT'` (WireMock이 수신 헤더 검증) | ❌ Wave 0 |
| GATE-02 | 클라가 보낸 X-User-* strip 후 게이트웨이 재설정(스푸핑 방지) | integration | `--tests '*GatewayRoutingIT'` (위조 헤더 전송 → downstream엔 게이트웨이 값만) | ❌ Wave 0 |
| GATE-03 | 누락/무효/만료 토큰 → 401, downstream 미도달 | integration | `--tests '*GatewayRoutingIT'` (WireMock 무호출 + 401 검증) | ❌ Wave 0 |
| — | JwtVerifier 서명/만료 단위검증 | unit | `--tests '*JwtVerifierTest'` (동일 secret 발급 토큰 파싱, 위조/만료 거부) | ❌ Wave 0 |
| — | 공개 경로(signup/login/refresh) 토큰 없이 통과 + strip 적용 | integration | `--tests '*GatewayRoutingIT'` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :api-gateway:test --tests '*JwtVerifierTest'` (빠른 단위)
- **Per wave merge:** `./gradlew :api-gateway:test`
- **Phase gate:** `./gradlew :api-gateway:build` green (Boot4/Spring Cloud 호환 최종 확정)

### Wave 0 Gaps
- [ ] `JwtVerifierTest.java` — verify-only 파싱: 동일 secret 발급 토큰 accept, 위조/만료/변조 reject
- [ ] `GatewayRoutingIT.java` — `@SpringBootTest(RANDOM_PORT)` + WireMock 스텁: 라우팅·헤더주입·strip·401 4종
- [ ] WireMock 의존 추가: `testImplementation 'org.wiremock:wiremock-standalone:3.9.2'`
- [ ] 테스트 HTTP 클라이언트: TestRestTemplate 미제공 대비 JDK `HttpClient`/`RestClient` 사용
- [ ] 테스트용 유효 토큰 생성 헬퍼(동일 secret으로 jjwt builder) — 또는 user-service JwtTokenProvider 재사용

## Security Domain

> security_enforcement 미명시 = 활성. 포함.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | 게이트웨이 집약 JWT 검증(HS256, jjwt). 발급은 user-service |
| V3 Session Management | yes | Stateless JWT(게이트웨이 세션 없음). access TTL 1h |
| V4 Access Control | yes | **클라 X-User-* strip(스푸핑 방지) = 이 phase 핵심 통제.** role 기반 인가는 Phase 3 |
| V5 Input Validation | partial | Authorization 헤더 형식(Bearer) 검증. 바디는 downstream 담당 |
| V6 Cryptography | yes | `Keys.hmacShaKeyFor`(≥256bit), HS256 대칭키. 자체 crypto 없음 |
| V13 API Security | yes | 단일 진입점, 미인증 401 차단(GATE-03) |

### Known Threat Patterns for Spring Cloud Gateway MVC + JWT
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| 클라가 X-User-Id/Role 위조 → 인가 우회 | Spoofing/Elevation | **모든 라우트에서 무조건 strip 후 게이트웨이 재설정** (비협상, GATE-02) |
| 토큰 없이 보호 리소스 접근 | Spoofing | before-filter 401 단락, downstream 미도달 (GATE-03) |
| 만료 토큰 재사용 | Spoofing | jjwt `parseSignedClaims`가 만료 자동 거부 → 401 |
| 약한/불일치 JWT_SECRET → 위조/전면장애 | Tampering/DoS | env 주입 ≥256bit, user-service와 동일 값. dev 기본값 프로덕션 제거(Phase1 D-P1-3 정합) |
| 게이트웨이 우회 downstream 직접 호출 | Spoofing | (아키텍처) downstream은 신뢰 네트워크 내부. 외부 노출은 게이트웨이만 — 배포 토폴로지 통제 |
| 알고리즘 혼동(alg=none/RS↔HS) | Tampering | jjwt `verifyWith(SecretKey)`는 HMAC만 허용 → alg 혼동 차단 |

## Sources

### Primary (HIGH)
- `git show main:{build.gradle,settings.gradle,docker-compose.yml}` — 루트 subprojects 서블릿 강제·플러그인/Boot 4.0.5 핀·포트맵 실측
- `git show origin/feat/user-product-resilience:user-service/.../JwtTokenProvider.java` + `SecurityConfig.java` — JWT 계약(HS256/subject/role/merchantId) + 공개경로(signup/login/refresh) 실측
- `user-service/src/main/resources/application.yml`, `user-service/build.gradle`, `GlobalExceptionHandler.java` — 포트 8085·JWT env·에러 envelope 실측
- `payment-service/.../CancelController.java` — 취소 경로 `/v1/payments/{paymentKey}/cancel` 실측
- Phase 1 `01-RESEARCH.md` — Boot 4.0.5/Security 7 실측 상속, jjwt 0.12.6 검증

### Secondary (MEDIUM)
- spring.io/blog/2025/11/25 (Spring Cloud 2025.1.0 Oakwood GA) — Boot 4 기반, gateway 아티팩트 개명
- spring.io/blog/2026/06/11 (Spring Cloud 2025.1.2) — Boot 4.0.7 대응
- central.sonatype.com — spring-cloud-starter-gateway-server-webmvc 5.0.0 존재 확인
- docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc — starter/RouterFunction 참조

## Metadata

**Confidence breakdown:**
- Boot4/Spring Cloud 호환 verdict: HIGH — 공식 블로그 + Maven Central 아티팩트 실측
- Standard stack / 좌표: HIGH — BOM·starter·jjwt 실측
- JWT 계약(신뢰 헤더): HIGH — user-service 소스 실측
- gateway-webmvc 필터 API 세부(strip 방식): MEDIUM(A3) — 빌드 시 API 확정
- BOM↔Boot 패치 정합: MEDIUM(A1) — phase gate 빌드로 확정

**Research date:** 2026-07-30
**Valid until:** 2026-08-29 (Spring Cloud 2025.1 라인 안정. Boot 4.0.x 내 유효)
