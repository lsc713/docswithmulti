# Phase 1: user-service 인증 기반 - Research

**Researched:** 2026-07-30
**Domain:** JWT 인증 (회원가입/로그인/토큰갱신/로그아웃) · Spring Security · 헥사고날 레이어링
**Confidence:** HIGH (참조 구현 전량 실측 · main 규약 실측)

## Summary

이 phase는 **설계가 아니라 이식(porting)**이다. 완전한 user-service가 `origin/feat/user-product-resilience`에
이미 존재하며 현재 main의 레이어 규약(domain/application/infrastructure/presentation 단방향, domain은 순수 POJO)을
이미 지킨다. AUTH-01~04에 필요한 코드는 전부 검증했고, 그대로 이식 가능한 상태다. 남은 작업은 (1) AUTH 범위 밖
파일 트림(Address/PaymentMethod/Admin/UserController 프로필 CRUD), (2) **main이 Spring Boot 4.0.5 / Spring
Security 7**라는 사실에 맞춘 소수의 조정, (3) 신규 모듈 배선(settings.gradle include, docker-compose DB, Flyway V1
트림)이다.

토큰 설계 실측 결과: access는 JWT(HMAC-SHA256, TTL 1h), **refresh는 JWT가 아니라 opaque UUID + DB 저장**
(TTL 7d). AUTH-04 로그아웃은 블랙리스트/플래그가 아니라 `refresh_tokens` 행 **하드 삭제**(userId 기준)로 무효화한다.
비밀번호는 BCrypt 기본 cost(10) 어댑터. 도메인 레이어는 이미 순수 POJO라 규약 조정 불필요.

**가장 큰 함정 두 가지:** (a) 참조 구현이 Boot 3.x 시절에 작성돼 "Boot 3.x" 가정이 문서에 남아있으나 **이식 대상 main은
Boot 4.0.5**다 — jjwt는 독립이라 무관하고 SecurityConfig는 이미 Security 7 호환 lambda DSL이라 컴파일은 통과하지만
빌드 검증이 필수. (b) `SignupRequest`가 클라이언트에게 `role`(USER/MERCHANT/ADMIN) 자기지정을 허용한다 — 권한
상승 취약점. 이식 시 서버가 USER로 강제해야 한다.

**Primary recommendation:** 참조 파일을 그대로 복사하되 AUTH 밖 파일을 트림하고, build.gradle은 root subprojects가
이미 제공하는 공통 의존성 위에 spring-security + jjwt 0.12.6만 추가한다. Boot 4로 빌드 검증 + signup role 서버강제 +
JWT_SECRET 기본값 제거를 조정 항목으로 처리한다.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTH-01 | 회원가입, 중복 이메일 거부 | `AuthService.signup` → `existsByEmail` 선검사 → `DuplicateEmailException`(409). `users.email UNIQUE` DDL이 2차 방어 |
| AUTH-02 | 로그인 → access+refresh JWT HMAC-SHA256 | `AuthService.login` → `createTokens`. access=JWT(HS256), refresh=opaque UUID+DB. `JwtTokenProvider.createAccessToken` |
| AUTH-03 | refresh로 access 갱신 | `AuthService.refresh(String)` → DB 조회 + `isExpired()` → 새 access만 발급(refresh 미회전) |
| AUTH-04 | 로그아웃 → refresh 무효화 | `AuthService.logout(userId)` → `refreshTokenRepository.deleteByUserId` (하드 삭제). 인증된 라우트(access 토큰 필요) |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 회원가입/로그인/갱신/로그아웃 API | API / Backend (user-service) | — | 신규 독립 모듈. 자체 발급, 외부 IdP 없음 |
| 비밀번호 해싱 | infrastructure/security | application(port) | BCrypt 어댑터가 `PasswordEncoder` 포트 구현 |
| JWT 발급·서명·검증 | infrastructure/security | — | `JwtTokenProvider` (jjwt). domain 밖 유지 |
| refresh 토큰 영속/무효화 | Database (user_db) | application(port) | opaque UUID를 `refresh_tokens`에 저장, 삭제로 무효화 |
| 요청 인증(access 토큰 파싱) | infrastructure/security 필터 | — | `JwtAuthenticationFilter` → SecurityContext. (게이트웨이 검증은 Phase 2) |
| 신원 도메인 규칙(상태/역할) | domain (순수 POJO) | — | `User`, `RefreshToken`, `UserRole`, `UserStatus` — Spring 의존 0 |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| spring-boot-starter-security | (Boot 4.0.5 관리 → Spring Security 7.x) | 필터체인, BCrypt, SecurityContext | main root build.gradle이 Boot 4.0.5 고정 [VERIFIED: git show main:build.gradle] |
| io.jsonwebtoken:jjwt-api | 0.12.6 (compile) | JWT 빌더/파서 API | 0.12.x 최신 라인, Spring과 독립이라 Boot 4 무관 [CITED: mvnrepository.com/artifact/io.jsonwebtoken/jjwt-impl] |
| io.jsonwebtoken:jjwt-impl | 0.12.6 (runtimeOnly) | jjwt 구현 | 모듈화된 jjwt는 api+impl+jackson 3분할 [CITED: jjwt install guide] |
| io.jsonwebtoken:jjwt-jackson | 0.12.6 (runtimeOnly) | JSON 직렬화 | 위와 동일 |

### Supporting (root subprojects가 이미 제공 — user-service build.gradle에 재선언 금지)
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| spring-boot-starter-web | Boot 4.0.5 | REST 컨트롤러 | 이미 subprojects 공통 [VERIFIED: main root build.gradle] |
| spring-boot-starter-validation | Boot 4.0.5 | `@Email`/`@NotBlank` DTO 검증 | 이미 공통 |
| spring-boot-starter-data-jpa | Boot 4.0.5 | 영속 | 이미 공통 |
| spring-boot-starter-flyway + flyway-core + flyway-mysql | 10.21.0 플러그인 | 마이그레이션 | 이미 공통 |
| com.mysql:mysql-connector-j | Boot 관리 | MySQL 8.0 드라이버 | 이미 공통 |
| lombok | Boot 관리 | `@RequiredArgsConstructor`, `@Getter` | 이미 공통 (AuthService/AuthController/BusinessException 사용) |
| spring-security-test | (test) | 필터/인가 테스트 | user-service build.gradle에 testImplementation 추가 |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| opaque refresh(UUID)+DB | refresh도 JWT | JWT refresh는 무효화 불가(만료 전까지 유효) → AUTH-04(로그아웃 무효화) 요구와 충돌. **현 설계(opaque+DB 삭제)가 요구에 정확히 부합** |
| BCrypt default cost 10 | Argon2/cost 12+ | 참조는 cost 10 기본. 이커머스 로그인 지연 감안 10 유지, 향후 상향은 인코더 교체만 |
| refresh 미회전 | refresh rotation on refresh | 회전 없으면 유출 시 7d 창. 스코프 내 미회전 유지하되 Open Question으로 명시 |

**Installation (user-service/build.gradle — 참조 그대로, root 공통 위에 얹음):**
```gradle
apply plugin: 'org.flywaydb.flyway'
apply plugin: 'jacoco'

flyway {
    url      = 'jdbc:mysql://localhost:3315/user_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
    user     = 'user'
    password = 'user'
    locations = ['classpath:db/migration']
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'
    testImplementation 'org.springframework.security:spring-security-test'
}
```

**Version verification:** jjwt 0.12.6은 0.12.x 최신(0.13.0도 존재하나 0.12.6 핀 안정) — Java 21 · Spring 무관. Maven Central
확인 [CITED]. Boot/Security 버전은 이식 대상 코드(`git show main:build.gradle`)에서 실측 = 4.0.5 → Security 7.x [VERIFIED].

## Package Legitimacy Audit

> 생태계는 **Maven** (npm/pypi/crates seam 비대상). 수동 검증.

| Package | Registry | Age | Source Repo | Verdict | Disposition |
|---------|----------|-----|-------------|---------|-------------|
| io.jsonwebtoken:jjwt-{api,impl,jackson} 0.12.6 | Maven Central | 수년 (jwtk) | github.com/jwtk/jjwt | OK | Approved [CITED: central.sonatype.com] |
| org.springframework.boot:spring-boot-starter-security | Maven Central | 공식 스프링 | github.com/spring-projects | OK | Approved |
| org.springframework.security:spring-security-test | Maven Central | 공식 스프링 | 동일 | OK | Approved |

**REMOVED:** 없음. **SUS:** 없음.

## Architecture Patterns

### System Architecture Diagram

```
[Client]
   │  POST /v1/auth/{signup,login,refresh}  (permitAll)
   │  POST /v1/auth/logout                   (authenticated: Bearer access)
   ▼
[AuthController]  (presentation, @Valid DTO)
   │  Command records (SignupCommand/LoginCommand)
   ▼
[AuthUseCase ← AuthService]  (application, @Transactional)
   ├─ existsByEmail / findByEmail / save ──▶ [UserRepository port] ─▶ [UserRepositoryImpl] ─▶ [UserJpaRepository] ─▶ (users)
   ├─ encode / matches ───────────────────▶ [PasswordEncoder port] ─▶ [BcryptPasswordEncoderAdapter (BCrypt cost 10)]
   ├─ createAccessToken (HS256 JWT) ───────▶ [JwtTokenProvider] ◀── jwt.secret (env JWT_SECRET)
   ├─ createRefreshToken (opaque UUID) ────▶ save/find/deleteByUserId ─▶ [RefreshTokenRepository port] ─▶ (refresh_tokens)
   ▼
[TokenResult → TokenResponse {accessToken, refreshToken}]

Inbound 인증(로그아웃 등 authenticated 라우트):
[Bearer access] ─▶ [JwtAuthenticationFilter] validate→ SecurityContext(principal=userId, ROLE_x) ─▶ [SecurityConfig chain]

domain(User·RefreshToken·UserRole·UserStatus): 순수 POJO. JpaEntity가 from()/toDomain()으로 매핑 (domain은 JPA 무의존)
```

### Recommended Project Structure (AUTH 트림 후 = 이식할 파일 전부)
```
user-service/src/main/java/com/example/user/
├── UserServiceApplication.java
├── domain/entity/            User, RefreshToken, UserRole, UserStatus            (순수 POJO)
├── application/
│   ├── usecase/AuthUseCase.java          (포트 + Command/Result records)
│   ├── service/AuthService.java          (@Service @Transactional)
│   └── interfaces/           UserRepository, RefreshTokenRepository, PasswordEncoder
├── infrastructure/
│   ├── security/             JwtTokenProvider, JwtAuthenticationFilter, SecurityConfig, BcryptPasswordEncoderAdapter
│   ├── persistence/          User{JpaEntity,JpaRepository,RepositoryImpl}, RefreshToken{JpaEntity,JpaRepository,RepositoryImpl}
│   └── config/PersistenceConfig.java     (⚠ 트림: user+refresh+encoder+jwt 빈만)
├── presentation/
│   ├── controller/AuthController.java
│   ├── dto/                  SignupRequest, LoginRequest, RefreshRequest, TokenResponse
│   └── GlobalExceptionHandler.java
├── common/exception/         BusinessException, ErrorCode
│   ├── application/          DuplicateEmailException, InvalidTokenException, UserNotFoundException
│   └── domain/               InvalidCredentialsException, SuspendedAccountException
└── resources/
    ├── application.yml        (server.port 8085, jwt.*, datasource user_db:3315)
    └── db/migration/V1__create_user_core.sql   (⚠ 트림: users + refresh_tokens 만)
```

### Pattern 1: 포트-어댑터 (헥사고날)
**What:** application이 인터페이스(port)에 의존하고 infrastructure가 구현. domain은 순수 POJO, JpaEntity가 `from()/toDomain()` 매핑.
**When:** 이 프로젝트의 main 레이어 규약 그 자체 — 신규 모듈은 반드시 따른다.
```java
// Source: origin/feat/user-product-resilience:.../infrastructure/config/PersistenceConfig.java (트림판)
@Bean UserRepository userRepository(UserJpaRepository jpa) { return new UserRepositoryImpl(jpa); }
@Bean RefreshTokenRepository refreshTokenRepository(RefreshTokenJpaRepository jpa) { return new RefreshTokenRepositoryImpl(jpa); }
@Bean PasswordEncoder passwordEncoder() { return new BcryptPasswordEncoderAdapter(); }
@Bean JwtTokenProvider jwtTokenProvider(@Value("${jwt.secret}") String s,
        @Value("${jwt.access-token-expiry}") long a, @Value("${jwt.refresh-token-expiry}") long r) {
    return new JwtTokenProvider(s, a, r);
}
```

### Pattern 2: 로그인 시 refresh 단일화(회전)
**What:** `login`이 `deleteByUserId` 후 새 refresh 발급 → 사용자당 활성 refresh 1개.
```java
// Source: .../application/service/AuthService.java
refreshTokenRepository.deleteByUserId(user.getId());
return createTokens(user);
```

### Anti-Patterns to Avoid
- **domain에 `@Entity`/`@Column` 부착:** 참조는 JpaEntity를 별도 두고 매핑 — 절대 병합 금지 (CLAUDE.md 불변식).
- **refresh를 JWT로:** 무효화(AUTH-04) 불가 → 요구 위반.
- **`ddl-auto: update`:** application.yml은 `validate` — DDL은 Flyway V1이 단일 소스. update 금지 (CLAUDE.md).
- **root subprojects 의존성 재선언:** web/jpa/validation/mysql/lombok/test는 이미 공통 — user-service build.gradle 중복 선언 금지.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWT 서명/검증 | 수동 HMAC + base64url | jjwt `Jwts.builder/parser` | 서명 검증·만료·클레임 파싱 엣지케이스 |
| 비밀번호 해싱 | 자체 salt+SHA | `BCryptPasswordEncoder` | salt 관리·타이밍 안전 비교 |
| 256-bit 키 생성 | `secret.getBytes()` 직접 서명 | `Keys.hmacShaKeyFor(bytes)` | 키 길이(<256bit) 검증 강제 (약한 키 거부) |
| 요청 인증 배선 | 컨트롤러마다 토큰 파싱 | `OncePerRequestFilter` + SecurityContext | 필터 단일 지점, permitAll/authenticated 선언 |

**Key insight:** 인증 원시요소는 전부 잘 검증된 라이브러리가 존재 — 자체 구현은 취약점 표면만 늘린다. 참조는 이미 이 원칙을 지킴.

## Runtime State Inventory

> 신규 모듈(greenfield) — 기존 런타임 상태를 rename/migrate하지 않는다. 5개 카테고리 전부 "해당 없음".

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — 신규 user_db, 기존 데이터 없음 | 없음 |
| Live service config | None — 신규 모듈, 기존 4개 서비스 설정 불변 | 없음 |
| OS-registered state | None | 없음 |
| Secrets/env vars | 신규 `JWT_SECRET` env var 도입(신규 키, 기존 시크릿 변경 없음) | 배포 환경에 JWT_SECRET 주입 (조정 항목 참조) |
| Build artifacts | None — 신규 모듈, 기존 산출물 영향 없음 | settings.gradle include 후 최초 빌드 |

## Port / Trim / Adjust Checklist

### PORT (그대로 복사 — `git show <branch>:<path>` 내용 사용)
- `domain/entity/{User,RefreshToken,UserRole,UserStatus}.java` — 순수 POJO, 무수정
- `application/usecase/AuthUseCase.java`, `application/service/AuthService.java`
- `application/interfaces/{UserRepository,RefreshTokenRepository,PasswordEncoder}.java`
- `infrastructure/security/{JwtTokenProvider,JwtAuthenticationFilter,SecurityConfig,BcryptPasswordEncoderAdapter}.java`
- `infrastructure/persistence/{User,RefreshToken}{JpaEntity,JpaRepository,RepositoryImpl}.java`
- `presentation/controller/AuthController.java`
- `presentation/dto/{SignupRequest,LoginRequest,RefreshRequest,TokenResponse}.java`
- `presentation/GlobalExceptionHandler.java`
- `common/exception/{BusinessException}.java`, `common/exception/application/{DuplicateEmailException,InvalidTokenException,UserNotFoundException}.java`, `common/exception/domain/{InvalidCredentialsException,SuspendedAccountException}.java`
- `UserServiceApplication.java`, `Dockerfile`
- 테스트: `AuthServiceTest`(Mockito), `JwtTokenProviderTest`, `UserTest`, `RefreshTokenTest`(순수 단위), `AuthControllerTest`, `UserRepositoryImplTest`+`AbstractRepositoryTest`(Testcontainers MySQL)

### TRIM (이식 금지 — AUTH-01~04 무관)
- 도메인/서비스/유즈케이스: `Address*`, `PaymentMethod*`, `PaymentMethodType`, `AdminService/AdminUseCase`, `UserService/UserUseCase`
- 인터페이스: `AddressRepository`, `PaymentMethodRepository`
- persistence: `Address*`, `PaymentMethod*` (JpaEntity/JpaRepository/RepositoryImpl)
- 컨트롤러: `AddressController`, `AdminController`, `PaymentMethodController`, `UserController`(프로필 CRUD)
- DTO: `AddressRequest/Response`, `AdminUserResponse`, `ChangePasswordRequest`, `PaymentMethodRequest/Response`, `UpdateUser*Request`, `UserResponse`
- 예외: `AddressNotFoundException`, `PaymentMethodNotFoundException`
- 테스트: `AddressServiceTest`, `AdminServiceTest`, `PaymentMethodServiceTest`, `UserServiceTest`, `AddressTest`, `PaymentMethodTest`
- **ErrorCode**: `ADDRESS_NOT_FOUND`, `PAYMENT_METHOD_NOT_FOUND` 항목 제거(선택). 나머지(USER_001~007, FORBIDDEN 등)는 유지.
- **⚠ 트림 부작용 주의:** `SecurityConfig`는 `/v1/admin/**` hasRole("ADMIN") 규칙을 포함 — Admin 컨트롤러를 트림하면 이 매처는 무해(도달 라우트 없음)하나, 참조와 일치시키려면 그대로 두거나 제거. `PersistenceConfig`는 Address/PaymentMethod 빈 참조를 **반드시 제거**(안 하면 미존재 JpaRepository 주입 실패로 컨텍스트 로드 실패).

### ADJUST (main 규약/버전에 맞춘 수정)
1. **settings.gradle:** `include 'user-service'` 추가 (main엔 아직 없음) [VERIFIED: git show main:settings.gradle].
2. **Spring Boot 3.x → 4.0.5 검증:** 참조는 Boot 3.x 시절 작성. main은 Boot 4.0.5 / Security 7. SecurityConfig는 이미
   lambda DSL(`csrf(c->c.disable())`, `authorizeHttpRequests`, `sessionManagement`)이라 Security 7 호환 [CITED: ankurm.com
   Spring Security 5→6→7 migration] — **컴파일/기동 검증만 필수**, 코드 수정 예상 없음. jjwt는 Spring 무관.
3. **`SignupRequest.role` 서버 강제 [보안, 필수]:** 현 참조는 클라가 `role`(USER/MERCHANT/ADMIN) 자기지정 → 권한 상승.
   AuthService.signup에서 role을 USER로 고정(또는 DTO에서 role 필드 제거). merchantId도 신뢰 입력으로 취급 금지.
4. **JWT_SECRET 기본값 [보안, 필수]:** application.yml `jwt.secret: ${JWT_SECRET:default-dev-secret...}`의 하드코딩
   dev 기본값은 로컬 전용 — 프로덕션 프로파일에서 기본값 제거해 미주입 시 기동 실패(fail-fast)하도록. CLAUDE.md 시크릿
   하드코딩 금지와 정합. env-var 주입 패턴 자체는 기존 서비스와 일치(로컬 인프라 creds 관례).
5. **docker-compose:** `mysql-user`(mysql:8.0, MYSQL_DATABASE=user_db, USER/PW=user/user, `"3315:3306"`, volume) 추가.
   기존 host 포트 3307~3311 사용 중 → 3315 충돌 없음 [VERIFIED: git show main:docker-compose.yml].
6. **Flyway V1 트림:** `V1__create_user_core.sql`에서 `addresses`·`payment_methods` CREATE 제거 → `users`+`refresh_tokens`만.
   `ddl-auto: validate`이므로 트림된 엔티티와 스키마 일치 필수.

## Common Pitfalls

### Pitfall 1: Boot 버전 가정 불일치
**What goes wrong:** "Boot 3.x" 가정으로 이식 → Security 6 API 기대. 실제 main은 Boot 4.0.5/Security 7.
**Why:** 참조 브랜치가 main보다 110커밋 뒤 + CLAUDE.md/phase goal이 "3.x"로 기재.
**How to avoid:** 이식 후 `./gradlew :user-service:build` 로 Boot 4 컴파일·기동 검증. SecurityConfig는 이미 lambda DSL이라 통과 예상.
**Warning signs:** `and()`/`authorizeRequests()` 등 제거된 API 사용 → 컴파일 에러 (참조엔 없음 — 안전).

### Pitfall 2: PersistenceConfig 트림 누락
**What goes wrong:** Address/PaymentMethod 빈 정의를 남기면 미존재 JpaRepository 주입 실패로 컨텍스트 로드 실패.
**How to avoid:** PersistenceConfig를 user/refresh/encoder/jwt/transactionManager 빈만 남기고 재작성.

### Pitfall 3: refresh 무효화 오해
**What goes wrong:** refresh를 JWT로 바꾸거나 블랙리스트 도입 시도.
**Why:** "JWT refresh" 통념. 실제 참조는 opaque UUID+DB, logout=하드삭제로 AUTH-04를 정확히 충족.
**How to avoid:** 참조 그대로 유지. `createRefreshToken()`은 `UUID.randomUUID()` — JWT 아님.

### Pitfall 4: signup 권한 상승
**What goes wrong:** 클라가 `"role":"ADMIN"`으로 회원가입 → 관리자 계정 self-provision.
**How to avoid:** ADJUST #3 — 서버가 USER 강제.

### Pitfall 5: HMAC 키 길이
**What goes wrong:** 256bit 미만 secret 주입 시 `Keys.hmacShaKeyFor`가 `WeakKeyException` 던짐 → 기동/발급 실패.
**How to avoid:** JWT_SECRET ≥ 32바이트(256bit). 기본값도 이미 그 이상 길이.

## Code Examples

### JWT access 발급 (HS256) — Source: infrastructure/security/JwtTokenProvider.java
```java
this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); // 256bit+ 강제
Jwts.builder().subject(String.valueOf(userId)).claim("role", role.name())
    .issuedAt(now).expiration(expiry).signWith(secretKey).compact();
```

### refresh 검증 + access 재발급 — Source: application/service/AuthService.java
```java
RefreshToken rt = refreshTokenRepository.findByToken(refreshToken).orElseThrow(InvalidTokenException::new);
if (rt.isExpired()) throw new InvalidTokenException(ErrorCode.EXPIRED_REFRESH_TOKEN);
User user = userRepository.findById(rt.getUserId()).orElseThrow(() -> new UserNotFoundException(rt.getUserId()));
return jwtTokenProvider.createAccessToken(user.getId(), user.getRole(), user.getMerchantId()); // refresh 미회전
```

### 로그아웃 = 무효화 — Source: application/service/AuthService.java
```java
@Transactional public void logout(long userId) { refreshTokenRepository.deleteByUserId(userId); }
```

### Flyway V1 (트림판, 이식 시 이 형태로) — Source: 참조 V1 + 트림
```sql
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    merchant_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_users_merchant_id (merchant_id),
    INDEX idx_users_status (status)
);
CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_refresh_tokens_user_id (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);
```

## Token Lifecycle Mechanics (질문 직접 답변)

| 질문 | 답 (VERIFIED via 참조 코드) |
|------|------|
| access TTL | 3,600,000 ms = **1시간** (application.yml `jwt.access-token-expiry`) |
| refresh TTL | 604,800,000 ms = **7일** (`jwt.refresh-token-expiry`) |
| HMAC-SHA256 시크릿 주입 | `jwt.secret` ← `${JWT_SECRET}` env → PersistenceConfig `@Value` → JwtTokenProvider 생성자 → `Keys.hmacShaKeyFor`. 알고리즘은 키 길이로 HS256 자동선택 (`signWith(secretKey)`) |
| refresh 저장 방식 | **DB `refresh_tokens` 테이블** (Redis 아님). opaque UUID 문자열, `expires_at` 컬럼 |
| refresh = JWT? | **아니오. opaque `UUID.randomUUID().toString()`** — 서명/클레임 없음 |
| AUTH-04 무효화 메커니즘 | **하드 DELETE** `deleteByUserId(userId)` — 블랙리스트/revoked 플래그 아님. 로그인 시에도 `deleteByUserId` 선행(사용자당 1개) |
| BCrypt cost | **기본 10** (`new BCryptPasswordEncoder()` — strength 인자 없음). 어댑터: infrastructure/security/BcryptPasswordEncoderAdapter |
| refresh 회전 | **없음** — refresh()는 새 access만 발급, TokenResponse.refreshToken=null |
| logout 라우트 인증 | authenticated (SecurityConfig permitAll에 logout 없음) → 유효 access 토큰의 principal(userId)로 자기 refresh만 삭제 |

## State of the Art

| Old Approach | Current Approach | Impact |
|--------------|------------------|--------|
| `WebSecurityConfigurerAdapter` | `SecurityFilterChain` 빈 + lambda DSL | 참조는 이미 신방식 — Security 7 호환 |
| jjwt 단일 `jjwt` 아티팩트 | api+impl+jackson 3분할 | 참조 build.gradle 이미 3분할 |
| `csrf().disable()` | `csrf(c -> c.disable())` | 참조 이미 신형. Security 7은 stateless API에도 CSRF 기본 확대 → 명시적 disable 유지가 정답 |

**Deprecated/outdated:** 참조 코드에 제거 대상 API 없음 (검증 완료).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | jjwt 0.12.6이 Boot 4.0.5/Java 21에서 무수정 동작 (Spring 독립) | Standard Stack | 낮음 — jjwt는 Spring 미의존. 빌드로 즉시 확인 |
| A2 | SecurityConfig lambda DSL이 Security 7에서 코드 수정 없이 컴파일 | ADJUST #2 | 중간 — 빌드 검증으로 확정 필요. 실패 시 소폭 API 조정 |
| A3 | BCrypt cost 10 유지 정책 (참조 기본값 존중) | Standard Stack | 낮음 — 정책 결정 사항. 사용자 확인 대상 |
| A4 | signup role 서버강제가 이 phase 스코프에 포함 | ADJUST #3 | 중간 — 보안상 필수지만 요구(AUTH-01)엔 명시 없음. discuss/plan에서 확인 |

## Open Questions

1. **refresh 회전(rotation) 도입 여부**
   - 아는 것: 참조는 미회전 — refresh 유출 시 7일 창.
   - 불명확: 이 마일스톤이 회전을 요구하는지 (REQUIREMENTS엔 무언급).
   - 권장: 스코프 내 **미회전 유지**(참조 그대로), 회전은 별도 개선 항목.

2. **signup의 role/merchantId 입력 정책**
   - 아는 것: 참조는 클라 자기지정 허용(권한 상승 위험).
   - 권장: signup은 USER 고정. MERCHANT/ADMIN 프로비저닝은 별도(Admin) 경로 — 단 Admin은 이 phase에서 트림됨. discuss-phase에서 확정.

3. **JWT_SECRET 프로덕션 주입 채널**
   - 아는 것: 기존 서비스는 로컬 creds를 yml/compose에 평문(관례). 프로덕션 시크릿 주입 방식은 문서 미확인.
   - 권장: k3s Secret/env로 주입, dev 기본값은 로컬 프로파일 한정.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| MySQL 8.0 (user_db) | 영속 | 신규 compose 서비스 필요 | 8.0 | 없음 — mysql-user 추가 필수 (ADJUST #5) |
| Java 21 | 빌드/런타임 | ✓ (프로젝트 고정) | 21 | 없음 |
| Gradle 8 | 빌드 | ✓ (Dockerfile gradle:8-jdk21) | 8 | 없음 |
| Docker (Testcontainers) | 리포지토리 통합테스트 | 통합테스트 실행 시 필요 | — | 단위테스트(Mockito)는 Docker 불요 |

**Blocking (fallback 없음):** compose에 `mysql-user`(3315) 미추가 시 Flyway/기동/통합테스트 불가 → ADJUST #5로 해결.

## Validation Architecture

> nyquist_validation 명시적 false 아님 → 포함.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + Testcontainers (MySQL 1.19.7) + spring-security-test. root subprojects 공통 [VERIFIED] |
| Config file | 없음 — `test { useJUnitPlatform() }` (root build.gradle) |
| Quick run | `./gradlew :user-service:test --tests '*AuthServiceTest' --tests '*JwtTokenProviderTest'` |
| Full suite | `./gradlew :user-service:test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| AUTH-01 | 회원가입 성공 + 중복 이메일 409 | unit | `:user-service:test --tests '*AuthServiceTest'` | ✅ 참조 AuthServiceTest.signup |
| AUTH-02 | 로그인 → access+refresh 발급, HS256 파싱 | unit | `--tests '*AuthServiceTest'` + `'*JwtTokenProviderTest'` | ✅ 참조 |
| AUTH-03 | 유효 refresh → 새 access, 만료/무효 refresh 거부 | unit | `--tests '*AuthServiceTest'` | ✅ 참조 refresh 테스트 |
| AUTH-04 | 로그아웃 → refresh 삭제 → 재갱신 거부 | unit | `--tests '*AuthServiceTest'` | ✅ 참조 logout 테스트 |
| AUTH-01~04 | HTTP 계약 (400/401/409) | slice | `--tests '*AuthControllerTest'` | ✅ 참조 AuthControllerTest |
| 영속 | User/RefreshToken 저장·조회 | integration (Testcontainers) | `--tests '*RepositoryImplTest'` | ✅ 참조 + AbstractRepositoryTest |

### Sampling Rate
- **Per task commit:** `./gradlew :user-service:test --tests '*AuthServiceTest'`
- **Per wave merge:** `./gradlew :user-service:test`
- **Phase gate:** `./gradlew :user-service:build` (Boot 4 컴파일+전체 테스트 green)

### Wave 0 Gaps
- 이식 대상 테스트가 참조에 존재 → 신규 작성 최소. 트림 후 **AuthServiceTest에서 signup role 서버강제(ADJUST #3) 검증 케이스 추가** 필요.
- 선택: domain 순수성 ArchUnit 테스트(archunit-junit5 이미 의존). 참조에 user-service ArchUnit 테스트 없음 — 도메인 순수성은 이미 성립하므로 optional.

## Security Domain

> security_enforcement 미명시 = 활성. 포함.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | BCrypt(cost 10) 비밀번호, HMAC-SHA256 JWT (jjwt) |
| V3 Session Management | yes | Stateless JWT + opaque refresh(DB), logout=삭제, `SessionCreationPolicy.STATELESS` |
| V4 Access Control | yes | **⚠ signup role 자기지정 취약(ADJUST #3)**. logout은 authenticated |
| V5 Input Validation | yes | jakarta validation (`@Email`,`@NotBlank`,`@NotNull`) DTO |
| V6 Cryptography | yes | `Keys.hmacShaKeyFor`(≥256bit 강제), BCrypt — 자체 구현 없음 |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| 약한/하드코딩 JWT 시크릿 → 토큰 위조 | Spoofing/Tampering | env `JWT_SECRET`(≥256bit) 주입, dev 기본값 프로덕션 제거 (ADJUST #4) |
| signup role=ADMIN 권한 상승 | Elevation of Privilege | 서버가 USER 강제, 클라 role 무시 (ADJUST #3) |
| refresh 유출 재사용(미회전) | Spoofing | logout 삭제로 축소. rotation은 Open Question |
| refresh 평문 저장(opaque UUID raw) | Info Disclosure | 위험 낮음(랜덤 opaque). DB 유출 대비 향후 해시 저장 고려 |
| SQL injection | Tampering | Spring Data JPA 파라미터 바인딩 (raw SQL 없음) |

## Sources

### Primary (HIGH)
- `git show origin/feat/user-product-resilience:user-service/**` — 참조 구현 전량 (security/application/domain/persistence/presentation/exception/build.gradle/application.yml/V1 DDL/Dockerfile/tests)
- `git show main:{settings.gradle,build.gradle,payment-service/build.gradle,docker-compose.yml}` — 이식 대상 규약·버전·포트 실측
- CLAUDE.md · .planning/workstreams/auth-gateway/{REQUIREMENTS,ROADMAP}.md · .planning/PROJECT.md

### Secondary (MEDIUM)
- mvnrepository.com / central.sonatype.com — jjwt 0.12.6 (0.12.x 최신) 확인
- ankurm.com "Spring Security 5→6→7 Migration" — lambda DSL / CSRF 변경

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 참조 build.gradle + main root 실측
- Architecture: HIGH — 파일 전량 판독, 레이어 규약 부합 확인
- Pitfalls: HIGH — Boot 버전/트림 부작용은 실측 근거
- Boot 4 컴파일 무수정: MEDIUM(A2) — 빌드 검증으로 확정 필요

**Research date:** 2026-07-30
**Valid until:** 2026-08-29 (안정 스택. 참조 브랜치 불변이면 무기한)
