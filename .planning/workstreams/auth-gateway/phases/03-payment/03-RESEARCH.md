# Phase 3: payment 취소 인가 (AUTHZ-01) - Research

**Researched:** 2026-07-30
**Domain:** payment-service 취소 인가 (신뢰 헤더 role 기반 authorization pre-check)
**Confidence:** HIGH (모든 핵심 사실을 실제 소스 + 참조 브랜치로 실측)

<user_constraints>
## User Constraints (locked)

### Locked Decisions (확정 인가 정책 — domain-rules.md에 없던 규칙, 이번 확정)
- `role=ADMIN` → 모든 결제 취소 허용.
- `role=MERCHANT` → `X-Merchant-Id == 대상 payment.merchantId` 일치할 때만 허용(본인 가맹점).
- 그 외(`USER`, role 누락) → **403 `CancelNotAuthorizedException`(FORBIDDEN_PAYMENT)**.
- payment는 게이트웨이가 준 신뢰 헤더 **role만** 신뢰. **JWT 재검증 없음**(게이트웨이 단일 검증).
- 신뢰 경계(payment 8080 직접 도달 스푸핑 방어)는 **k3s NetworkPolicy로 배포 시점 이관** — 코드는 게이트웨이 경유 가정 + 문서화. 앱 레벨 공유 시크릿 검증은 이번 스코프 아님.

### Claude's Discretion
- 인가 삽입 지점(레이어/파일), 신뢰 헤더 읽기 방식, authz 클래스 배치, 테스트 격리 방식.

### Deferred Ideas (OUT OF SCOPE)
- 앱 레벨 공유 시크릿/HMAC 헤더 검증 (NetworkPolicy로 대체).
- X-User-Id를 CancelRequest canceller 필드에 기록 (취소 코어 write path 접촉 → defer).
- payment 존재 은닉(403 vs 404 leak 방지). 현재 무인증 대비 순개선이므로 이번 미대응.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| AUTHZ-01 | payment가 신뢰 헤더 role로 취소 인가, 무권한은 취소 플로우 진입 전 403, JWT 재검증 없음 | 아래 §인가 삽입 지점(presentation pre-check), §신뢰 헤더 읽기, §PORT/ADAPT, §테스트 전략 |
</phase_requirements>

## Summary

payment-service는 현재 **인증/인가가 전혀 없다**(`build.gradle`에 spring-security 미포함, `CancelController`는 무인증 동작). 이번 phase는 게이트웨이가 주입한 신뢰 헤더(`X-User-Role`/`X-User-Id`/`X-Merchant-Id`)의 **role만으로** 취소 인가를 수행하는 순수 pre-check를 취소 코어 **바깥**에 얹는다.

핵심 자산이 이미 준비돼 있다: `ErrorCode.FORBIDDEN_PAYMENT`(403) **존재**, 참조 브랜치 `origin/feat/user-product-resilience`에 `CancelNotAuthorizedException extends BusinessException(FORBIDDEN_PAYMENT)`·`AuthenticatedUser`·JWT security 자산 **존재**, `GlobalExceptionHandler`가 `BusinessException`을 이미 status/code로 매핑(**핸들러 무변경**). 참조의 JWT 재검증 자산(`JwtAuthenticationFilter`/`SecurityConfig`)은 우리 게이트웨이-집약-검증 원칙과 충돌하므로 **채택 금지**.

**Primary recommendation:** 인가를 **presentation 경계(CancelController → 새 `CancelAuthorizationUseCase`)의 pre-check**로 넣어 `cancelPaymentUseCase.cancel()` **호출 이전에** 403을 던진다. 인가 판정 규칙은 **domain 순수 POJO `CancelAuthorizer`**에, payment 로드는 **read-only application service**에 둔다. 이렇게 하면 취소 코어 파일(`CancelPaymentService`·`CancelTxWriter`·`CancelDomainService`·스케줄러·outbox·messaging)은 **byte-for-byte 무변경** — "취소 코어 불변"을 파일 단위로 증명 가능. 신뢰 헤더는 `@RequestHeader` 직접 읽기(spring-security 의존 추가 금지, 필터 금지).

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| 신뢰 헤더 수신/파싱 | Presentation (CancelController) | — | HTTP 경계 관심사, `@RequestHeader` |
| 인가 오케스트레이션(payment 로드 + 위임) | Application (신규 read-only `CancelAuthorizationService`) | — | 저장소 접근은 application, 취소 write path와 분리 |
| 인가 판정 규칙(role→권한, 소유권 비교) | Domain (신규 순수 POJO `CancelAuthorizer`) | — | 비즈니스 규칙, Spring/JPA 금지 |
| 403 응답 매핑 | Presentation (기존 `GlobalExceptionHandler`) | — | `BusinessException`→status 이미 존재, 무변경 |
| 취소 실행(멱등성·TX1/2/3·스케줄러·Kafka) | Application/Domain (기존 `CancelPaymentService` 등) | — | **이번 phase 무변경 (인가 게이트 통과 후 그대로 호출)** |
| payment 직접도달 스푸핑 방어 | Infra/배포 (k3s NetworkPolicy) | — | 배포 시점 이관(코드 아님), Phase1 Secret 이관 패턴 |

## 인가 삽입 지점 — 권고 (Question 1, 4)

### 권고: Presentation 경계 pre-check (Option A) — 취소 코어 파일 무변경

**흐름 (권한 있는 요청):**
```
CancelController.cancel()
  1. @RequestHeader로 X-User-Role / X-User-Id / X-Merchant-Id 읽기
  2. cancelAuthorizationUseCase.authorize(paymentKey, role, headerMerchantId, userId)
       → 통과(void) 또는 CancelNotAuthorizedException(403) throw
  3. (통과 시) cancelPaymentUseCase.cancel(command)   ← 기존 코어, 완전 무변경
```

**취소 코어 불변 논증 (파일/레이어 단위):**
- `cancel()` 이전에 인가가 끝나므로, `CancelPaymentService.cancel()`의 Step1 조회·`request_hash`·`dedup_key`·멱등성 분기·TX1/2/3·이력·daily_limit 3단·스케줄러·Kafka **어느 라인도 수정하지 않는다**.
- **diff가 닿는 파일:** `CancelController.java`(presentation), 신규 `CancelAuthorizationUseCase`/`CancelAuthorizationService`(application), 신규 `CancelAuthorizer`(domain), PORT된 `CancelNotAuthorizedException`(common), 신규 `AuthenticatedUser`(선택). **diff가 닿지 않는 파일:** `CancelPaymentService`·`CancelTxWriter`·`CancelDomainService`·`CancelHistoryRecorder`·`*RecoveryService`·`CompensationRetryService`·outbox/messaging 전체.
- 인가는 부작용 없는 read-only pre-check(payment SELECT 1회 + 순수 비교) → 취소 상태 머신에 진입하지 않음. `git diff --stat`으로 코어 파일 0 변경을 검증 가능(→ Validation Architecture Wave0 게이트).

**payment.merchantId 조회 트레이드오프 (명시적 결정):**
- MERCHANT 소유권 검증에는 대상 `payment.merchantId`가 필요 → 인가 경로가 payment를 1회 read-only 로드. 이후 코어 Step1이 다시 로드 → **MERCHANT 경로에서 payment 이중 조회**.
- ADMIN/USER/누락 경로: 소유권 불필요. ADMIN은 즉시 통과(로드 생략 가능), USER/누락은 즉시 403(로드 불필요) → **이중 조회는 MERCHANT 경로에서만** 발생.
- 비용: `payment_key` 유니크 인덱스 SELECT 1회. risk HTTP + PG HTTP + TX 3개 대비 <1% → **무시 가능**. 정합성 위험 없음(read-only, 취소는 멱등).
- `ponytail: 이중 조회 감수하고 코어 파일 불변을 택함. 조회 병목이 실측되면 그때 Option B로 통합.`

### Alternative (참고): Option B — `cancel()` 초반 재사용 pre-check
`CancelPaymentService.cancel()` Step1 payment 로드 직후·Step2(request_hash) 이전에 guard 1줄 삽입(로드 재사용 → 이중 조회 없음). 단 **취소 코어 파일을 접촉**(command에 auth 필드 + guard 호출). 코어 불변식(멱등성/TX/스케줄러/Kafka)은 여전히 무변경이나 "파일 단위 증명"이 약해짐. 조회 성능이 실증적으로 문제될 때만 채택 권고. **이번 기본 아님.**

## 신뢰 헤더 읽기 방식 — 권고 (Question 2, 3, 6)

### 권고: `@RequestHeader` 직접 읽기 + spring-security 미추가 + 필터 없음
- **spring-security 의존 추가 금지.** 게이트웨이가 이미 단일 검증 → payment는 신뢰 헤더 소비만. `SecurityConfig`(anyRequest().authenticated() + JWT 필터)를 추가하면 무인증으로 도는 내부 엔드포인트·기존 취소 통합테스트가 깨진다.
- **필터/인터셉터 불필요.** 인가 대상 엔드포인트가 취소 1곳 → 컨트롤러 `@RequestHeader`가 최소. `ponytail: HandlerMethodArgumentResolver는 2번째 authz 엔드포인트 생기면 그때. 지금은 YAGNI.`
- **헤더 계약(Phase 2 실측 고정, `02-RESEARCH.md`):** 게이트웨이가 클라 위조 X-User-* strip 후 검증값 주입 —
  - `X-User-Id` = JWT subject(=userId, String) [CITED: 02-RESEARCH.md L243]
  - `X-User-Role` = claim `role`(역할명 문자열) [CITED: 02-RESEARCH.md L244]
  - `X-Merchant-Id` = claim `merchantId`(**옵션 — 있을 때만** 주입, `String.valueOf(Long)`) [CITED: 02-RESEARCH.md L245]
- **파싱 방어:** `X-User-Role` null/blank → 무권한(403). `X-Merchant-Id`는 **MERCHANT인데 헤더 없음 → 403**(소유권 검증 불가). String→Long 파싱 실패(비정상 값)는 500이 아니라 **403**으로 흡수(`NumberFormatException` catch → null 취급).
- **role 비교는 String equality**(`"ADMIN".equals(role)`), UserRole enum 불필요(role은 문자열 claim, Phase 2와 동일 판단). merchantId 비교: `Long headerMerchantId`(nullable) vs `long payment.getMerchantId()` → null 가드 후 `headerMerchantId.equals(targetMerchantId)` 값 비교.

### 인가 판정 위치(레이어) — domain 순수 POJO
```
// domain/service/CancelAuthorizer.java  (순수, Spring/JPA 없음)
public void authorize(String role, Long headerMerchantId, Long targetMerchantId) {
    if ("ADMIN".equals(role)) return;
    if ("MERCHANT".equals(role)
            && headerMerchantId != null
            && targetMerchantId != null
            && headerMerchantId.equals(targetMerchantId)) return;
    throw new CancelNotAuthorizedException();   // FORBIDDEN_PAYMENT(403)
}
```
- application `CancelAuthorizationService`: MERCHANT일 때만 payment 로드(없으면 `PaymentNotFoundException`→404), `targetMerchantId` 계산 후 domain authorizer 호출. ADMIN/USER/누락은 `null` target으로 위임(로드 생략). domain은 순수 → 매트릭스 단위테스트가 빠르고 exhaustive.
- domain은 outer 타입(`AuthenticatedUser`, repository) 의존 금지 → **primitive(String/Long)만 전달**. `AuthenticatedUser` 레코드를 쓰려면 presentation/application 레이어 carrier로만 두고 domain 경계에서 primitive로 unpack. `ponytail: 엔드포인트 1개면 record 생략하고 primitive 직전달도 가능 — 가독성 위해 최소 record는 선택.`

### X-User-Id 용도 (Question 6)
- 이번 정책(ADMIN+MERCHANT)에서 **인가 판정에 미사용**. userId는 소유권 비교 대상 아님(참조 브랜치의 USER self-cancel 규칙은 **우리 정책에서 제거**).
- **감사 로깅용으로만** 활용(누가 취소 시도했는지 log line). CancelRequest `canceller_type`/`cancelled_by` 필드에 기록하는 것은 취소 코어 write path 접촉이므로 **이번 defer**(Deferred Ideas).

## PORT / ADAPT / DROP 체크리스트 (참조 브랜치 payment security 자산)

| 자산 (`origin/feat/user-product-resilience`) | 조치 | 근거 |
|---|---|---|
| `common/exception/domain/CancelNotAuthorizedException.java` | **PORT (그대로 복사)** | `extends BusinessException(FORBIDDEN_PAYMENT)`. `GlobalExceptionHandler`가 이미 403 매핑 → 핸들러 무변경. [VERIFIED: git show] |
| `infrastructure/security/AuthenticatedUser.java` | **ADAPT** | (1) `validateCancelAuthorization`의 **USER self-cancel 분기 제거**(우리 정책 USER→403). (2) 판정 로직을 domain `CancelAuthorizer`로 이전, 레코드는 순수 carrier(userId, role, merchantId)로 축소. (3) infra→application/presentation로 재배치. **또는** 레코드 생략하고 primitive 직전달. |
| `infrastructure/security/JwtAuthenticationFilter.java` | **DROP** | JWT 재검증 = 게이트웨이 단일 검증 원칙 위반(locked). 채택 금지. [VERIFIED: git show — Jwts.parser().verifyWith 재검증] |
| `infrastructure/security/SecurityConfig.java` | **DROP** | `@EnableWebSecurity` + `anyRequest().authenticated()` + JWT 필터 체인. spring-security 의존 유발 + 무인증 내부 엔드포인트/기존 테스트 회귀. 채택 금지. [VERIFIED: git show] |
| 신규: domain `CancelAuthorizer` | **CREATE** | 순수 판정 규칙(위 코드). |
| 신규: application `CancelAuthorizationUseCase`/`CancelAuthorizationService` | **CREATE** | read-only 오케스트레이션(payment 로드 + 위임). 기존 `CancelPaymentUseCase` 패턴 미러. |

## Package Legitimacy Audit

> 이번 phase는 **외부 패키지를 설치하지 않는다**. 신뢰 헤더는 기존 `spring-boot-starter-web`의 `@RequestHeader`로 읽고, 예외는 기존 `BusinessException` 계층 재사용. spring-security·jjwt 등 **추가 없음**.

**Packages removed due to [SLOP] verdict:** none (신규 의존 없음)
**Packages flagged as suspicious [SUS]:** none

## Architecture Patterns

### 인가 pre-check 배치 (System 흐름)
```
[gateway가 신뢰헤더 주입한 POST /v1/payments/{key}/cancel]
        │  X-User-Role, X-User-Id, X-Merchant-Id
        ▼
CancelController (@RequestHeader 읽기)
        │
        ▼
CancelAuthorizationUseCase.authorize(paymentKey, role, headerMerchantId, userId)
        │        ┌─ ADMIN ───────────────► 통과 (payment 로드 생략)
        │        ├─ MERCHANT ─► payment 로드(404 if absent) ─► CancelAuthorizer
        │        │                                  ├ merchantId 일치 ► 통과
        │        │                                  └ 불일치/헤더없음 ► 403 throw
        │        └─ USER / 누락 ─────────────────────────────────► 403 throw
        ▼ (통과)
cancelPaymentUseCase.cancel(command)   ◄── 기존 취소 코어, 무변경
        │  TX1 → risk → TX2 → PG → TX3 → outbox/Kafka
        ▼
200 (권한 role은 기존 플로우 그대로)
```

### Recommended 구조 (신규/수정 파일)
```
presentation/controller/CancelController.java        # (수정) 헤더 읽기 + authorize 호출
application/usecase/CancelAuthorizationUseCase.java   # (신규) interface
application/service/CancelAuthorizationService.java   # (신규) read-only 오케스트레이션
domain/service/CancelAuthorizer.java                  # (신규) 순수 판정 규칙
common/exception/domain/CancelNotAuthorizedException.java  # (PORT)
application(or presentation)/.../AuthenticatedUser.java    # (선택 ADAPT) carrier
```

### Anti-Patterns to Avoid
- **취소 코어 파일에 authz 삽입**(Option B를 기본으로): 파일 단위 불변 증명 약화. 조회 병목 실증 전엔 지양.
- **spring-security/JWT 필터 추가**: 게이트웨이 단일 검증 원칙 위반 + 회귀.
- **domain에서 repository/AuthenticatedUser 의존**: 레이어 규약 위반. primitive만 전달.
- **파싱 실패를 500으로**: 비정상 X-Merchant-Id는 403으로 흡수.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 403 응답 매핑 | 컨트롤러 try/catch·별도 핸들러 | 기존 `GlobalExceptionHandler` (BusinessException→status) | 이미 존재, 무변경 |
| 403 에러코드 | 신규 enum | 기존 `ErrorCode.FORBIDDEN_PAYMENT` | 이미 존재 [VERIFIED] |
| 인가 예외 | 신규 정의 | PORT `CancelNotAuthorizedException` | 참조 브랜치 존재, 계층 일치 |
| 헤더 파싱 | 커스텀 필터/resolver | `@RequestHeader(required=false)` | 엔드포인트 1개, YAGNI |

**Key insight:** 이 phase의 자산 대부분이 이미 존재(ErrorCode·핸들러·예외·헤더 계약). 순수 신규는 판정 규칙(domain POJO) + read-only 오케스트레이션뿐 — 최소 diff.

## Common Pitfalls

### Pitfall 1: X-Merchant-Id 부재
**무엇:** merchantId claim은 **옵션** → MERCHANT 토큰엔 있지만 헤더가 없을 수도. null인데 `==` 비교 시 NPE 또는 오통과.
**회피:** null 가드 후 `equals`. MERCHANT인데 headerMerchantId==null → 403.
**징후:** MERCHANT 요청이 500(NPE) 또는 소유권 우회.

### Pitfall 2: USER self-cancel 규칙 잔존
**무엇:** 참조 `AuthenticatedUser.validateCancelAuthorization`는 `USER && userId==payment.userId` 허용. 우리 정책은 **USER→403**.
**회피:** ADAPT 시 USER 분기 **삭제**. 단위테스트에 "USER는 owner여도 403" 명시.

### Pitfall 3: 기존 CancelControllerTest 회귀
**무엇:** `new CancelController(cancelPaymentUseCase)` → authz 의존 추가 시 컴파일 깨짐.
**회피:** 생성자에 authz usecase mock 추가. 기존 테스트는 authz mock을 **no-op(void 기본)**으로 두면 헤더 없이도 통과(실제 판정은 신규 테스트에서). 헤더 추가 불필요.
**징후:** 기존 멱등성 테스트가 갑자기 403.

### Pitfall 4: 이중 payment 조회를 성능 이슈로 오판
**무엇:** MERCHANT 경로 payment 2회 로드 우려로 코어(Option B) 접촉 유혹.
**회피:** 유니크 인덱스 SELECT 1회는 무시 가능. 실측 병목 아니면 코어 불변 유지.

## Runtime State Inventory

> 코드/신규-클래스 추가 phase(런타임 상태 rename/마이그레이션 아님). 아래 전 카테고리 "None" 확인.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | None — DB 스키마/키/컬럼 변경 없음(인가는 read-only, 저장 안 함) | none |
| Live service config | None — 게이트웨이 헤더 계약은 Phase 2에서 완료·고정 | none |
| OS-registered state | None — 스케줄러/Task 변경 없음 | none |
| Secrets/env vars | None — JWT 시크릿은 payment 미보유(게이트웨이 단일 검증). 앱 시크릿 검증 스코프 밖 | none |
| Build artifacts | None — 신규 의존 없음, 재빌드만 | `./gradlew :payment-service:build` |

## Code Examples

### 컨트롤러 헤더 읽기 + pre-check
```java
// CancelController.java (수정 — cancel() 시그니처에 헤더 추가, 본문 최상단에 authorize)
@PostMapping("/{paymentKey}/cancel")
public ResponseEntity<CancelPaymentResponse> cancel(
    @PathVariable String paymentKey,
    @RequestBody @Valid CancelPaymentRequest request,
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    @RequestHeader(value = "X-User-Role", required = false) String role,
    @RequestHeader(value = "X-User-Id", required = false) String userId,
    @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId
) {
    // 취소 플로우 진입 전 인가 (실패 시 CancelNotAuthorizedException → 403)
    cancelAuthorizationUseCase.authorize(paymentKey, role, userId, merchantId);
    // ↓ 이하 기존 로직 무변경 (Idempotency-Key 처리 + cancelPaymentUseCase.cancel)
    ...
}
```
`// Source: 기존 CancelController.java 실측 패턴 확장`

### 판정 규칙 (domain 순수)
```java
// domain/service/CancelAuthorizer.java
public void authorize(String role, Long headerMerchantId, Long targetMerchantId) {
    if ("ADMIN".equals(role)) return;
    if ("MERCHANT".equals(role) && headerMerchantId != null
            && targetMerchantId != null && headerMerchantId.equals(targetMerchantId)) return;
    throw new CancelNotAuthorizedException();
}
// Source: 참조 AuthenticatedUser.validateCancelAuthorization ADAPT (USER 분기 제거)
```

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Mockito + `spring-test` MockMvc(standalone) |
| Config file | none (스탠드얼론) |
| Quick run command | `./gradlew :payment-service:test --tests '*CancelAuthorizer*' --tests '*CancelController*'` |
| Full suite command | `./gradlew :payment-service:test` |

> **Boot 4 테스트 관행 (실측):** 프로젝트 컨트롤러 테스트는 `@AutoConfigureMockMvc`/`TestRestTemplate`가 아니라 **`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())` + `@ExtendWith(MockitoExtension.class)`**로 usecase mock. [VERIFIED: CancelControllerTest.java L38-49]. 신규 authz 테스트도 **이 패턴 그대로** 확장.

### Phase Requirements → Test Map
| Req | Behavior | Test Type | Automated Command | File Exists? |
|-----|----------|-----------|-------------------|-------------|
| AUTHZ-01 | ADMIN 허용 / MERCHANT match 허용 / mismatch 403 / USER 403 / role 누락 403 / merchantId 누락 403 | unit(domain) | `pytest→` `./gradlew :payment-service:test --tests '*CancelAuthorizerTest'` | ❌ Wave 0 |
| AUTHZ-01 | payment 없음→404, ADMIN 로드 생략, MERCHANT 로드 후 위임 | unit(Mockito) | `--tests '*CancelAuthorizationServiceTest'` | ❌ Wave 0 |
| AUTHZ-01 | 컨트롤러가 헤더 읽고 cancel 이전 authorize 호출; 403 매핑; 통과 시 cancel 호출 | web(standalone MockMvc) | `--tests '*CancelControllerTest'` | ⚠️ 확장 |
| AUTHZ-01(SC#1) | ADMIN 취소가 **기존 취소 플로우(멱등성·TX 불변)로 정상 처리** | integration | 기존 `CancelFlowIntegrationTest`에 `X-User-Role: ADMIN` 헤더 추가 | ⚠️ 확장 |
| 코어 불변 | 취소 코어 파일 0 변경 | gate | `git diff --name-only` 에 `CancelPaymentService`/`CancelTxWriter`/`CancelDomainService`/scheduler 부재 | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew :payment-service:test --tests '*CancelAuthorizer*' --tests '*CancelController*'`
- **Per wave merge:** `./gradlew :payment-service:test`
- **Phase gate:** payment-service 풀 스위트 green + 코어 파일 diff 0 확인 후 `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `domain/service/CancelAuthorizerTest.java` — 인가 매트릭스 6종(순수, 빠름)
- [ ] `application/service/CancelAuthorizationServiceTest.java` — 로드/404/위임(Mockito)
- [ ] `CancelControllerTest.java` **확장** — 헤더→authorize 호출 순서, 403 매핑, 통과 시 cancel 호출 (기존 파일에 추가, 생성자 mock 갱신)
- [ ] `CancelFlowIntegrationTest.java` **확장** — happy path에 ADMIN 신뢰 헤더 부착(SC#1 증명)

## Security Domain

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V4 Access Control | **yes (핵심)** | role 기반 인가 pre-check(ADMIN/MERCHANT 소유권), 기본 거부(USER/누락→403) |
| V5 Input Validation | yes | 신뢰 헤더 파싱 방어(null/blank/비정상 merchantId→403, 500 아님) |
| V2 Authentication | no | 게이트웨이 단일 검증(payment 재검증 없음, locked) |
| V6 Cryptography | no | payment는 JWT 시크릿 미보유, 서명검증 안 함 |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| payment 8080 직접 도달로 신뢰헤더 위조 → 인가 우회 | Spoofing/Elevation | **k3s NetworkPolicy(게이트웨이만 payment ingress 허용)로 배포 시점 이관.** 코드는 게이트웨이 경유 가정. Phase1 Secret 이관 동일 패턴 |
| MERCHANT가 타 가맹점 결제 취소 | Elevation of Privilege | `X-Merchant-Id == payment.merchantId` 소유권 검증, 불일치/누락 403 |
| USER가 인가 획득 | Elevation | 정책상 USER→403(참조 self-cancel 규칙 제거) |
| 비정상 헤더로 500 유발(정보 노출/DoS) | DoS | 파싱 예외를 403으로 흡수 |

### NetworkPolicy 문서화 항목 (배포 이관 — 코드 아님)
아래를 phase 산출물(예: `docs/architecture.md` 또는 배포 문서)에 **명문화**하고 코드 주석(`CancelAuthorizer`/`CancelAuthorizationService`)에 신뢰 경계 가정을 남긴다:
- **가정:** payment-service(8080) ingress는 **게이트웨이 파드에서만** 허용(k3s `NetworkPolicy` podSelector/namespaceSelector). 클라 직접 도달 차단.
- **이유:** payment는 헤더 role을 **무검증 신뢰** → 네트워크 계층이 신뢰 경계를 강제해야 함(앱 레벨 공유 시크릿 검증은 이번 스코프 밖).
- **이관 시점:** 배포(k3s scale-out) phase — Phase1 k3s Secret 이관과 동일 패턴. 코드 phase에서는 문서 + 주석만.
- **미이관 리스크(문서화):** NetworkPolicy 부재 시 payment 직접 호출로 X-User-Role: ADMIN 위조 → 전량 취소 가능. **배포 전 필수 게이트**로 표기.

## State of the Art

| Old (참조 브랜치) | Current (이 설계) | 근거 |
|---|---|---|
| payment가 JWT 재검증(`JwtAuthenticationFilter`+`SecurityConfig`) | 게이트웨이 단일 검증 + payment는 신뢰 헤더 role 소비 | locked: downstream 재검증 없음 |
| `AuthenticatedUser.validateCancelAuthorization`에 USER self-cancel 허용 | USER→403(정책 확정) | locked 인가 정책 |
| 앱 레벨 인증 필터 | 신뢰 경계는 k3s NetworkPolicy(배포 이관) | locked 신뢰 경계 |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | 기존 무인증 내부 엔드포인트가 있어 SecurityConfig 추가 시 회귀 | 신뢰헤더 읽기 | 낮음 — SecurityConfig 어차피 DROP 권고라 무영향 |
| A2 | MERCHANT 토큰엔 merchantId claim 항상 존재(→헤더 존재) | Pitfall 1 | 있으면 정상; 없으면 403(안전측). null 가드로 이미 방어 |

**나머지 모든 사실은 실측 검증됨:** ErrorCode.FORBIDDEN_PAYMENT·GlobalExceptionHandler·CancelController·Payment.merchantId·참조 브랜치 security 자산·Boot 4.0.5·헤더 계약(Phase2)·테스트 관행 = 모두 소스/브랜치 직접 확인.

## Open Questions

1. **AuthenticatedUser 레코드 유지 vs primitive 직전달**
   - 아는 것: 엔드포인트 1개 → primitive로 충분. 레코드는 가독성/향후 확장.
   - 권고: 선택 사항. 최소 diff면 primitive, 명료성이면 얇은 레코드(application 레이어). 계획 시 택1.
2. **api-spec.md에 신뢰 헤더/403 명시 여부**
   - 아는 것: error-catalog.md엔 FORBIDDEN_PAYMENT 존재[VERIFIED]. api-spec 요청헤더 표엔 신뢰 헤더 부재(게이트웨이 내부 주입이라 클라 관점엔 불투명).
   - 권고: domain-rules.md에 **인가 정책(신규 규칙) 명문화**(도메인 규칙 원본), error-catalog는 그대로, api-spec은 403 응답 note만 선택 추가.

## Environment Availability

> 외부 신규 의존 없음. 신규 도구/서비스 불필요. (재빌드만: `./gradlew :payment-service:build`)

## Sources

### Primary (HIGH)
- `payment-service/.../CancelController.java`, `CancelPaymentService.java`, `ErrorCode.java`, `BusinessException.java`, `GlobalExceptionHandler.java`, `Payment.java` — 실측
- `payment-service/.../CancelControllerTest.java` — Boot4 테스트 관행 실측(standalone MockMvc)
- `git show origin/feat/user-product-resilience:` AuthenticatedUser / SecurityConfig / JwtAuthenticationFilter / CancelNotAuthorizedException — 실측
- `sysdesign/cancel-design.md` — 취소 코어 불변식(TX1/2/3·멱등성·스케줄러·Kafka)
- `.planning/workstreams/auth-gateway/phases/02-api-gateway-jwt/02-RESEARCH.md` — 신뢰 헤더 계약(X-User-Id/Role/Merchant-Id)
- `docs/error-catalog.md` L60-64,156 — FORBIDDEN_PAYMENT(403) 존재
- 루트 `build.gradle` — Spring Boot 4.0.5, Java 21, spring-security 미포함

## Metadata

**Confidence breakdown:**
- 인가 삽입 지점/코어 불변: HIGH — 코어 흐름·파일 경계 실측
- 신뢰 헤더 계약: HIGH — Phase2 RESEARCH + user-service 실측 인용
- PORT/ADAPT: HIGH — 참조 브랜치 자산 직접 확인
- 테스트 전략: HIGH — 기존 테스트 파일 패턴 실측

**Research date:** 2026-07-30
**Valid until:** 2026-08-29 (안정 — 코어 미변경, 참조 자산 고정)
