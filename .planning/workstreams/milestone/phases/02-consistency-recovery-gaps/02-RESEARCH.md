# Phase 2: 정합성 & 복구 갭 마감 - Research

**Researched:** 2026-07-28
**Domain:** Spring Boot 내부 결제취소 도메인 — HTTP 클라이언트 스텁 구현 · 낙관적 동시성(UK 레이스) · 스케줄러 원자 UPDATE
**Confidence:** HIGH (전부 기존 코드베이스 실측 기반 — 신규 라이브러리·외부 미검증 API 없음)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

- **D-01 (PG 상태조회 계약):** `PgCancelHttpClient.getStatus()`는 운영 PG가 **취소 상태조회 엔드포인트를 제공한다는 전제**로 그 계약에 맞춰 구현한다(응답 → `PgCancelResult` APPROVED/FAILED/PENDING + retryable 매핑). 정확한 엔드포인트 경로/응답 스키마는 researcher가 확인·정의. — **Reversibility: costly** — 복구 로직(`ProcessingRecoveryService.recoverOne`)이 getStatus 조회 계약에 결합된다. PG가 조회 미지원(fire-and-forget)으로 밝혀지면 "멱등 재취소로 복구" 설계로 갈아엎어야 한다.
- **D-05:** `RiskManagementHttpClient.isCharged()`는 신규 구현이 아니라 **이미 존재하는** `GET /internal/cancel-limit/check`(`CheckChargeUseCase` → `{charged,...}`)에 배선한다. — **Reversibility: reversible.**
- **D-02 (동시성 테스트 재현 범위):** 멀티파드 레이스(RESIL-02)·스케줄러 동시 실행(RESIL-03) 결함은 **Testcontainers(실 MySQL) + 같은 JVM 동시 스레드**(CountDownLatch/ExecutorService)로 재현한다. 실 UK 위반·원자 UPDATE를 실 DB로 검증. 실제 2 인스턴스 구동은 하지 않음(CI 부담/오케스트레이션 대비 이득 낮음). — **Reversibility: reversible.**

### Claude's Discretion (선택 영역 밖 — 코드/스펙이 이미 규정)

- **D-03 (멱등 응답 시맨틱, RESIL-02):** `api-spec.md`가 이미 규정 — 진행 중이면 **200 + `status: PENDING/PROCESSING`**. 레이스 패자는 `CancelPaymentService.executeCancel`의 `saveTx1` PENDING INSERT에서 UK 위반(DataIntegrityViolation)을 catch → `findByPaymentIdAndRequestHash` 재조회 → 기존 CancelRequest를 `CancelPaymentService:71`의 상태 스위치(COMPLETED/PENDING/PROCESSING→기존건 반환)로 흘려 동일 계약 준수. 새 응답 형태를 만들지 않는다. — **Reversibility: costly** — api-spec.md의 공개 응답 계약. 형태 변경은 클라이언트 계약 파기.
- **D-04 (동시성 가드 강도, RESIL-03):** 스케줄러 Redis 분산락이 이미 단일 실행을 보장하므로, (a) `pg_retry_count`는 객체 mutation+save가 아닌 **DB 원자 UPDATE**로 교정(필수 위생), (b) **레코드 단위 분산락은 추가하지 않는다**(락 만료/실패 대비는 현재 YAGNI). — **Reversibility: reversible** — 레코드 락은 필요 시 후속 추가 가능.

### Deferred Ideas (OUT OF SCOPE)

- **레코드 단위 분산락(cancelRequestId 멱등 가드):** 스케줄러 Redis 락 만료/실패 대비 방어. 현재 YAGNI(D-04) — 필요 시 후속 페이즈.
- **실 PG 상태조회 계약 근거 문서:** 사용자 제공 시 canonical ref로 등록, getStatus 구현의 authoritative source. (이번 세션에서 사용자 제공 문서 없음 — 아래 D-01 설계는 researcher의 관례적 REST 설계, `[ASSUMED]`.)
- **Phase 1 의존:** 로드맵상 Phase 2는 Phase 1 실측 기준선에 의존(복구/레이스 수정의 회귀를 기준선으로 검증). Phase 1 미착수 상태 — planning 시 Phase 1 산출물 부재를 감안.
- **성능/용량 개선(→ Phase 4), 배포/노드 HA(→ Phase 3), 신규 기능:** 명시적 범위 밖.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RESIL-01 | `PgCancelHttpClient.getStatus()`/`RiskManagementHttpClient.isCharged()` 스텁 제거 → PROCESSING 5분 초과 건 자동 복구 | D-01 PG 상태조회 REST 계약 설계(Standard Stack/Code Examples) + D-05 기존 `/internal/cancel-limit/check` 배선(Code Examples) + TX3 재실행 원자성 근거(Architecture Patterns) |
| RESIL-02 | 멀티파드 동시 취소 레이스 패자 500→멱등 200 | `CancelPaymentService.executeCancel` saveTx1 DataIntegrityViolationException catch 패턴(Code Examples, Common Pitfalls) — 신규 응답 형태 없음, api-spec.md §멱등성 처리 응답 재사용 |
| RESIL-03 | ProcessingRecovery 동시성 가드(pg_retry_count 원자 UPDATE) | `MerchantCancelUsageJpaRepository.tryDeduct/tryRestore` 원자 UPDATE 컨벤션 이식(Don't Hand-Roll, Code Examples) + Testcontainers 동시성 테스트 패턴(`MerchantCancelUsageAtomicDeductIT`) |
</phase_requirements>

## Summary

이 페이즈는 신규 기능이 아니라 **이미 설계된 코드 경로의 미완성 구간**을 채우는 작업이다. 세 요구사항 모두 코드베이스 안에 정답에 가까운 참조점이 이미 존재한다: RESIL-01은 `MockPgCancelClient.getStatus`와 `scheduler-enhancement-design.md`가 계약 형태를 이미 규정했고, RESIL-02는 `CancelPaymentService`의 FAILED 재시도 분기(`handleExistingRequest`)가 이미 동일한 "UK 충돌 → 재조회 → 상태 스위치" 패턴을 쓰고 있어 그대로 확장하면 되며, RESIL-03은 `risk-management-service`의 `tryDeduct`/`tryRestore`가 이미 "객체 mutation 대신 원자 UPDATE" 컨벤션과 그 Testcontainers 동시성 테스트 패턴(`MerchantCancelUsageAtomicDeductIT`)까지 증명해뒀다. 새 라이브러리는 필요 없다 — 기존 `RestTemplate` + `resilience4j CircuitBreaker` + `Spring Data JPA @Modifying @Query` + `Testcontainers`만으로 충분하다.

유일한 진짜 미지수는 D-01(PG 상태조회 REST 계약)이다. 근거 문서가 없으므로 이 문서는 `PgCancelHttpClient.cancel()`의 기존 POST 계약과 `PgCancelResult` 레코드 구조를 그대로 미러링한 **관례적 REST 계약을 `[ASSUMED]`로 설계**했다 — 이는 CONTEXT.md가 명시한 "costly reversibility"를 안고 가는 결정이며, 실제 PG 문서가 나오면 갈아엎어야 할 수 있다.

코드 정독 과정에서 두 가지 실측 발견이 있었다: (1) `CancelRequestJpaEntity`가 `GenerationType.IDENTITY`를 쓰므로 `saveTx1`의 UK 위반은 `cancelTxWriter.saveTx1()` 호출 시점에 **동기적으로** 던져진다 — catch 지점이 정확히 CONTEXT.md가 지목한 곳이 맞다. (2) TX3 재실행 동시 경합 시 두 번째 스레드가 `PaymentItem.cancel()`에서 `InvalidPaymentItemStatusException`(→`BusinessException`)을 맞는데, `ProcessingRecoveryService.recoverOne()`의 현재 catch 블록이 이걸 "도메인 규칙 위반 — 데이터 정합성 문제"로 **오탐 ERROR 로깅**한다 — 이건 데이터 손상이 아니라 벤치성 레이스의 정상 패자다. Common Pitfalls 참조.

**Primary recommendation:** 세 요구사항 모두 "새로 설계"가 아니라 "코드베이스 안의 자매 패턴을 그대로 이식"하는 방식으로 구현하라. D-01만 예외적으로 신규 설계(REST 계약)가 필요하며 `[ASSUMED]`로 표시하고 `checkpoint:human-verify`로 게이트한다.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| PG 취소 상태조회 (getStatus) | API/Backend (payment-service infrastructure/http) | — | 외부 PG HTTP 어댑터, Port/Adapter 패턴의 `!local` 프로파일 구현체 |
| risk 차감여부 조회 (isCharged) | API/Backend (payment-service infrastructure/http) → risk-management-service | Database (risk_db, `cancel_usage_history`) | 기존 `/internal/cancel-limit/check`를 서비스 간 HTTP로 호출, 원본 데이터는 risk-management DB |
| 레이스 패자 멱등 응답 | API/Backend (payment-service application/service) | Database (MySQL UK 제약) | 비즈니스 로직은 애플리케이션 계층이 소유하되, "정확성"의 최종 보증은 DB UK가 물리적으로 담당 — 앱 코드는 그 위반을 "번역"만 한다 |
| pg_retry_count 원자 갱신 | Database (payment_db, `cancel_request.pg_retry_count`) | API/Backend (repository 원자 UPDATE 쿼리) | read-modify-write 경쟁을 없애려면 갱신 로직 자체를 SQL 단일 문장으로 DB에 위임해야 함(앱 계층 락으로는 불충분) |
| 스케줄러 동시 실행 조정 | API/Backend (Redis 분산락, 기존 구현됨) | — | 이미 구현된 스케줄러 계층 책임 — Phase 2는 락 자체를 만들지 않고, 락이 없어도 안전한 데이터 갱신(원자 UPDATE)만 보강 |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Web `RestTemplate` | Spring Boot 4.0.5 관리 버전(코드베이스 고정) [VERIFIED: build.gradle] | PG/risk HTTP 클라이언트 | 기존 `PgCancelHttpClient`/`RiskManagementHttpClient`가 이미 이 방식 — 새 HTTP 클라이언트 도입 불필요 |
| resilience4j `CircuitBreaker` | 코드베이스 고정(`ResilienceConfig`) [VERIFIED: 코드] | getStatus/isCharged 호출도 기존 `pgCancelCircuitBreaker`/`riskManagementCircuitBreaker` 재사용 | 신규 브레이커 만들 필요 없음 — 같은 PG/risk 대상이므로 기존 인스턴스 그대로 |
| Spring Data JPA `@Modifying @Query` | Spring Boot 4.0.5 관리 버전 [VERIFIED: 코드] | pg_retry_count 원자 UPDATE | `MerchantCancelUsageJpaRepository.tryDeduct/tryRestore`가 이미 같은 패턴 증명 |
| Testcontainers MySQL 8.0 | 1.19.7 [VERIFIED: `.planning/codebase/TESTING.md`] | RESIL-02/03 동시성 재현 | `AbstractRepositoryTest`(payment-service), `MerchantCancelUsageAtomicDeductIT`(risk-management-service) 기존 패턴 재사용 |

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `java.util.concurrent.ExecutorService` + `CountDownLatch` | JDK 21 표준 라이브러리 | RESIL-02/03 동시 스레드 레이스 재현 | `MerchantCancelUsageAtomicDeductIT.concurrent_deducts_never_over_and_never_spurious_reject()`가 정확히 이 패턴 — 그대로 이식 |
| `DataIntegrityViolationException` (Spring `org.springframework.dao`) | Spring Framework 코드베이스 관리 버전 | RESIL-02 UK 위반 감지 | Spring Data JPA 리포지토리는 `@Repository` 스테레오타입 예외 변환이 자동 적용되어 JDBC `ConstraintViolationException`을 이 타입으로 번역함 — 신규 의존성 아님, 이미 클래스패스에 있음 |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `saveTx1` UK 위반 catch (D-03) | Redis 분산락으로 request_hash 선점 | 락 오버헤드 추가 + 락 만료 시 이중 처리 가능성 → UK가 이미 물리적으로 보장하므로 불필요한 인프라 추가. api-spec.md 계약과도 무관 |
| pg_retry_count 원자 UPDATE (D-04) | `cancelRequestId` 레코드별 분산락 | CONTEXT.md D-04가 명시적으로 YAGNI 처리 — Redis 락 관리 부담 대비 이득 낮음. 원자 UPDATE만으로 "카운터 유실 없음" 요구는 충족됨(중복 PG 재호출 자체를 막는 건 아님, Common Pitfalls 참조) |
| getStatus REST 계약(D-01) | fire-and-forget PG(조회 API 없음) 가정 후 "멱등 재취소로 복구" | CONTEXT.md가 이 대안의 존재를 명시(costly reversibility 사유) — 실 PG 문서 확보 전까지는 채택하지 않음, 관례적 조회 계약으로 우선 진행 |

**Installation:** 없음 — 이 페이즈는 신규 패키지를 추가하지 않는다. `build.gradle`에 이미 존재하는 `spring-boot-starter-web`, `resilience4j-spring-boot3`, `spring-boot-starter-data-jpa`, `testcontainers-mysql`만 사용.

## Package Legitimacy Audit

이 페이즈는 외부 패키지를 신규로 설치하지 않는다 — `PgCancelHttpClient`/`RiskManagementHttpClient`의 스텁 구현과 `ProcessingRecoveryService`의 원자 UPDATE 보강은 모두 기존 의존성(`RestTemplate`, `resilience4j`, Spring Data JPA, Testcontainers)만으로 완료된다. 패키지 정합성 게이트 대상 없음 — **스킵**.

## Architecture Patterns

### System Architecture Diagram

```
[payment 파드 A]                          [payment 파드 B]
POST /v1/payments/{key}/cancel            POST /v1/payments/{key}/cancel  (동시 착지, 동일 payment)
      │                                          │
      ▼                                          ▼
CancelPaymentService.executeCancel        CancelPaymentService.executeCancel
      │                                          │
      ▼                                          ▼
cancelTxWriter.saveTx1() ── INSERT ──▶  [MySQL] cancel_request
      │  (승자: 커밋 성공)                        │  (패자: UK(payment_id,request_hash) 위반
      │                                          │   → DataIntegrityViolationException)
      ▼                                          ▼
   TX2→PG→TX3 정상 진행                    ★신규: catch → findByPaymentIdAndRequestHash
      │                                          │   → handleExistingRequest 상태 스위치
      ▼                                          ▼
   200 COMPLETED/PROCESSING                200 PENDING/PROCESSING/COMPLETED (승자 상태 그대로 반환)
                                            (500 아님 — RESIL-02 목표)

────────────────────────────────────────────────────────────────

[processing-recovery 스케줄러] (Redis 분산락으로 다중 인스턴스 중 1개만 실행)
      │
      ▼
findProcessingUpdatedBefore(now-5분)
      │
      ▼
pgCancelPort.getStatus(paymentKey)  ★신규 구현(D-01): GET {pg}/v1/payments/{paymentKey}/cancel/status
      │
      ├─ 조회 실패(예외) ─────────────▶ PROCESSING 유지, 다음 주기 재시도
      ├─ APPROVED ────────────────────▶ cancelTxWriter.saveTx3() 재실행 (원자 rollback으로 자연 멱등)
      ├─ FAILED, retryable=true ──────▶ pg_retry_count 원자 UPDATE ★신규(D-04) → 재호출
      ├─ FAILED, retryable=false ─────▶ riskManagementPort.compensate() → FAILED
      └─ PENDING ─────────────────────▶ markPgPending, 1시간 초과 시 보상+알림

[pending-recovery 스케줄러]
      │
      ▼
findPendingCreatedBefore(now-5분)
      │
      ▼
riskManagementPort.isCharged(cancelRequestId)  ★신규 배선(D-05): GET risk/internal/cancel-limit/check?cancelRequestId=
      │
      ├─ charged=true ─▶ compensate → FAILED
      └─ charged=false ─▶ FAILED (보상 불필요)
```

### Recommended Project Structure

기존 구조를 그대로 사용 — 신규 파일은 최소(신규 리포지토리 메서드 + 신규 테스트 클래스뿐):

```
payment-service/src/main/java/com/example/payment/
├── infrastructure/http/
│   ├── PgCancelHttpClient.java        # getStatus() 구현 (수정)
│   └── RiskManagementHttpClient.java  # isCharged() 배선 (수정)
├── application/service/
│   ├── CancelPaymentService.java      # executeCancel에 DataIntegrityViolationException catch 추가 (수정)
│   └── ProcessingRecoveryService.java # retryPgCancel의 pg_retry_count 갱신을 원자 UPDATE 호출로 교체 (수정)
├── application/interfaces/
│   └── CancelRequestRepository.java   # incrementPgRetryCount(long id) 메서드 추가 (수정)
└── infrastructure/persistence/
    ├── CancelRequestJpaRepository.java     # @Modifying @Query 원자 UPDATE 추가 (수정)
    └── CancelRequestRepositoryImpl.java    # 위임 추가 (수정)

payment-service/src/test/java/com/example/payment/
├── infrastructure/http/
│   └── PgCancelHttpClientTest.java    # 신규: getStatus 매핑 단위 테스트
├── application/service/
│   └── CancelPaymentServiceTest.java  # 레이스 패자 멱등 단위 테스트 추가
└── integration/
    ├── CancelRaceIdempotencyIT.java        # 신규: RESIL-02 Testcontainers 동시성
    └── ProcessingRecoveryConcurrencyIT.java # 신규: RESIL-03 Testcontainers 동시성
```

### Pattern 1: UK 위반을 멱등 응답으로 번역 (RESIL-02)

**What:** `saveTx1`의 PENDING INSERT가 `(payment_id, request_hash)` UK를 위반하면, 새 오류를 만들지 않고 기존 레코드 재조회 → 기존 `handleExistingRequest` 상태 스위치로 흘린다.
**When to use:** 멀티파드에서 동일 취소 요청이 동시에 두 인스턴스에 착지하는 모든 경로.
**Example:**
```java
// Source: 코드베이스 CancelPaymentService.java:53-64 (기존 패턴) 확장
// executeCancel 내부, saveTx1 호출부만 발췌
CancelRequest cancelRequest = CancelRequest.create(
    payment.getId(), requestHash, cancelAmount, command.cancelReason(),
    command.cancelPaymentItemIds());
try {
    cancelRequest = cancelTxWriter.saveTx1(cancelRequest);
} catch (org.springframework.dao.DataIntegrityViolationException e) {
    // 레이스 패자: 승자가 이미 INSERT함. UK(payment_id, request_hash) 위반.
    // 신규 응답 형태 금지(D-03) — 기존 handleExistingRequest로 그대로 위임.
    CancelRequest winner = cancelRequestRepository
        .findByPaymentIdAndRequestHash(payment.getId(), requestHash)
        .orElseThrow(() -> e); // 이론상 불가(방금 위반났으므로 존재) — 방어적 재throw
    return handleExistingRequest(winner, command, payment, items);
}
recordHistory(cancelRequest.getId(), CancelStatus.PENDING, null);
// ... 이하 기존 로직 그대로
```
**중요:** `CancelRequestJpaEntity`는 `GenerationType.IDENTITY`([VERIFIED: 코드 `CancelRequestJpaEntity.java:32-33`])이므로 `save()` 호출 시점에 즉시 INSERT가 실행되고, UK 위반은 `cancelTxWriter.saveTx1()` 메서드 안(REQUIRES_NEW TX)에서 **동기적으로** 발생해 호출자에게 예외로 전파된다. 지연 flush를 걱정할 필요 없음 — catch 지점은 정확히 `saveTx1()` 호출 직후여야 한다.

### Pattern 2: 원자 UPDATE로 read-modify-write 경쟁 제거 (RESIL-03)

**What:** 도메인 객체를 메모리에서 mutate 후 save하는 대신, SQL 단일 문장으로 DB에서 직접 증가시킨다.
**When to use:** 여러 실행 주체(스케줄러 중복 실행 등)가 같은 카운터 필드를 동시에 갱신할 가능성이 있는 모든 곳.
**Example:**
```java
// Source: 코드베이스 risk-management-service/.../MerchantCancelUsageJpaRepository.java:44-57 (기존 패턴)
// 그대로 이식할 신규 코드 (CancelRequestJpaRepository)
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE CancelRequestJpaEntity c SET c.pgRetryCount = c.pgRetryCount + 1 WHERE c.id = :id")
int incrementPgRetryCount(@Param("id") long id);
```
```java
// ProcessingRecoveryService.retryPgCancel() 수정 — 기존 mutation+save 제거
// Before (읽기-수정-쓰기 경쟁):
//   cancelRequest.incrementPgRetryCount();
//   cancelRequestRepository.save(cancelRequest);
// After (원자 UPDATE):
cancelRequestRepository.incrementPgRetryCount(cancelRequest.getId());
// ⚠ 갱신 후 cancelRequest.getPgRetryCount()는 여전히 갱신 전 값(stale in-memory).
// 이후 로직(재시도 임계값 5회 체크)이 이 값을 쓰므로 반드시 재조회 필요:
CancelRequest refreshed = cancelRequestRepository
    .findByPaymentIdAndRequestHash(cancelRequest.getPaymentId(), cancelRequest.getRequestHash())
    .orElseThrow();
if (refreshed.getPgRetryCount() >= MAX_PG_RETRIES) { compensateAndFail(refreshed, payment); }
```
**핵심 함정:** 원자 UPDATE는 "DB 값"만 정확하게 만든다. 호출부의 로컬 `cancelRequest` 객체는 자동으로 갱신되지 않으므로, 임계값 비교 직전에 반드시 재조회하라. 그렇지 않으면 "카운터는 정확한데 5회 제한 로직이 stale 값으로 오판"하는 새로운 버그가 생긴다.

### Anti-Patterns to Avoid

- **레코드 단위 분산락 추가(RESIL-03):** CONTEXT.md D-04가 명시적으로 배제. 스케줄러 자체는 이미 Redis 분산락으로 단일 인스턴스만 실행되므로, "동시 인스턴스" 시나리오는 락 만료 같은 예외적 상황에서만 발생한다. 원자 UPDATE만으로 "카운터 유실 0"이라는 요구사항 충족 가능 — 추가 락은 YAGNI.
- **UK 위반을 글로벌 `@ExceptionHandler`로 200 매핑:** `GlobalExceptionHandler`에 `DataIntegrityViolationException → 200` 핸들러를 추가하면 안 된다. 이 예외는 다른 UK 제약(예: 향후 추가될 테이블)에서도 발생할 수 있어 전역 매핑은 의미가 오염된다. catch는 반드시 `CancelPaymentService.executeCancel`의 `saveTx1` 호출 지점에 국소적으로.
- **PG getStatus 실패를 즉시 FAILED 처리:** 기존 설계(`ProcessingRecoveryService.recoverOne:56-62`)가 이미 "조회 실패 → PROCESSING 유지"로 올바르게 처리 중이다. 스텁 구현 시 이 catch 블록의 의미를 재검토하되(현재는 `UnsupportedOperationException`도 이 블록에 걸려 무동작을 흉내내고 있었음), 진짜 네트워크 실패와 스텁 예외를 혼동하지 않도록 스텁 제거 후 반드시 실제 실패 경로로 테스트해야 한다.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| 동시 카운터 증가 | 애플리케이션 레벨 락(synchronized, Redisson 레코드 락) | SQL `UPDATE ... SET x = x + 1 WHERE id = :id` | 단일 SQL 문장은 MySQL InnoDB 행 레벨 잠금으로 원자성이 이미 보장됨 — `tryDeduct`/`tryRestore`가 이미 이 패턴으로 검증됨. 애플리케이션 락은 다중 파드 환경에서 별도 인프라(Redis) 필요하고 이 문제엔 과함 |
| 중복 취소 요청 차단 | 자체 멱등성 캐시(Redis SETNX 등) | 기존 `(payment_id, request_hash)` UK 제약 | DB가 이미 물리적으로 exactly-once를 보장(CLAUDE.md 불변식) — 애플리케이션 레벨 캐시를 추가하면 캐시-DB 정합성이라는 새 문제가 생김 |
| PG 상태 폴링 재시도 스케줄링 | 커스텀 백오프 루프 | 기존 `processing-recovery` 스케줄러(60초 주기, Redis 분산락) | 이미 존재하는 스케줄러 인프라를 그대로 사용 — `getStatus()`는 이 스케줄러가 호출하는 포트 메서드만 구현하면 됨 |

**Key insight:** 이 페이즈의 세 요구사항은 전부 "동시성 정합성"이라는 같은 문제의 변주이며, 이 코드베이스는 이미 그 정답 패턴(DB UK, DB 원자 UPDATE, 분산락)을 다른 위치에서 증명해뒀다. 새 동시성 프리미티브를 발명하지 말고 그 패턴을 그대로 복제하라.

## Common Pitfalls

### Pitfall 1: TX3 동시 재실행 시 정상 레이스가 "정합성 위반"으로 오탐 로깅됨

**What goes wrong:** 두 스케줄러 실행(또는 최초 요청과 복구가 겹치는 경합)이 같은 `cancelRequest`에 대해 `runTx3`를 동시에 시도하면, `saveTx3`의 `findAllByPaymentIdForUpdate()`가 행 락으로 순서를 강제한다. 승자가 커밋하면 패자는 재조회한 `PaymentItem`이 이미 `CANCELLED`임을 보고 `PaymentItemStatusPolicy.validateCancellableStatus()`가 `InvalidPaymentItemStatusException`을 던진다(`PaymentItem.java:55-57` [VERIFIED: 코드]). 이 예외는 `BusinessException`을 상속하므로([VERIFIED: `InvalidPaymentItemStatusException extends BusinessException`]), `ProcessingRecoveryService.recoverOne()`의 `catch (BusinessException e)` 블록(`ProcessingRecoveryService.java:71-73`)에 걸려 **"도메인 규칙 위반 — 데이터 정합성 문제"로 ERROR 레벨 로깅**된다.
**Why it happens:** 이 catch 블록은 원래 "진짜 데이터 손상"(예: cancel_item_ids가 가리키는 아이템이 없음)을 위해 설계됐으나, 동시성 재실행이라는 벤치성(harmless) 레이스도 같은 예외 타입을 발생시킨다.
**How to avoid:** RESIL-03 구현 시 이 로그 레벨/메시지가 오해를 유발하지 않도록 재검토하라 — 데이터는 손상되지 않았다(승자가 정확히 1건 COMPLETED 처리했고, 패자는 그저 재시도 실패). 최소한 이 로그가 alerting을 트리거하지 않는지 확인하고, 가능하면 "동시 복구 충돌(무해)"와 "진짜 정합성 위반"을 구분하는 별도 처리를 고려하되, **CONTEXT.md D-04가 레코드 락 추가를 배제했으므로 이 구분 로직 자체를 새 방어막으로 만들지는 말 것** — 로깅 명확화 수준에서 그친다.
**Warning signs:** Testcontainers 동시성 테스트에서 두 스레드가 같은 `cancelRequestId`로 `recoverOne`(또는 `saveTx3`)을 동시에 호출했을 때 한쪽이 `InvalidPaymentItemStatusException`을 던지는 것 자체는 **정상**이다 — 테스트는 이걸 실패로 보지 말고 "최종적으로 정확히 1건 COMPLETED"만 검증해야 한다.

### Pitfall 2: pg_retry_count 원자 UPDATE 이후 로컬 객체가 stale

**What goes wrong:** `incrementPgRetryCount(id)` 원자 UPDATE를 호출한 직후 `cancelRequest.getPgRetryCount() >= MAX_PG_RETRIES`처럼 메모리상의 객체를 그대로 비교하면, 그 값은 UPDATE 이전 값이라 재시도 임계값 로직이 한 박자 늦게 반응한다(최악의 경우 6~7회까지 재시도하거나, 반대로 다른 스레드가 이미 5회를 채웠는데도 계속 재호출).
**Why it happens:** JPQL bulk `@Modifying` 쿼리는 영속성 컨텍스트를 우회해 DB를 직접 갱신하며, 자바 객체의 필드는 자동으로 동기화되지 않는다(`MerchantCancelUsageJpaRepository.tryDeduct`가 `clearAutomatically = true`로 1차 캐시를 지우는 이유가 정확히 이것).
**How to avoid:** 원자 UPDATE 직후 임계값을 비교해야 하는 지점에서는 반드시 재조회하라(Code Examples Pattern 2 참조). `clearAutomatically = true`를 설정하면 최소한 "다음 조회"가 stale 1차 캐시를 안 보게는 되지만, **호출자가 들고 있는 로컬 참조 자체는 여전히 재할당해야** 한다.
**Warning signs:** 단위 테스트에서 `incrementPgRetryCount` 호출 후 곧바로 같은 인메모리 `cancelRequest` 객체의 getter로 임계값을 판단하는 코드가 있다면 버그.

### Pitfall 3: D-01 REST 계약이 실제 PG와 다를 위험 (근거 문서 없음)

**What goes wrong:** `PgCancelHttpClient.getStatus()`의 엔드포인트 경로·응답 스키마·인증 방식이 실제 운영 PG와 다르면, 복구 로직 전체가 잘못된 계약 위에 지어진다.
**Why it happens:** 이 프로젝트에 PG 상태조회 API의 근거 문서가 없다(CONTEXT.md 확인). `[ASSUMED]`로 설계.
**How to avoid:** 아래 Code Examples의 계약은 기존 `cancel()`(POST `/v1/payments/{paymentKey}/cancel`) 계약과 `PgCancelResult` 레코드 구조를 그대로 미러링한 것이다. 실제 PG 연동 전(스테이징/샌드박스) `checkpoint:human-verify`로 게이트하고, 사용자가 실 PG 문서를 제공하면 CONTEXT.md canonical_refs에 등록 후 이 계약을 갱신해야 한다.
**Warning signs:** 이 계약대로 구현한 `PgCancelHttpClient.getStatus()`가 실제 PG 게이트웨이(`external.pg.url=http://pg-gateway:443`)에 대해 한 번도 실제 호출 검증되지 않은 채 머지되면 위험 신호.

## Code Examples

### D-05: `isCharged()` — 기존 엔드포인트 배선 (신규 설계 없음)

```java
// Source: risk-management-service 기존 엔드포인트, 코드 확인 완료 [VERIFIED: 코드]
// GET /internal/cancel-limit/check?cancelRequestId={id}
// → InternalCancelLimitController.check() → CheckChargeUseCase.execute(String cancelRequestId)
// → CheckChargeResponse(cancelRequestId: String, charged: boolean, merchantId: Long, cancelAmount: BigDecimal)

// RiskManagementHttpClient.isCharged 구현
@Override
public boolean isCharged(long cancelRequestId) {
    try {
        return circuitBreaker.executeCheckedSupplier(() -> {
            String url = baseUrl + "/internal/cancel-limit/check?cancelRequestId={cancelRequestId}";
            ResponseEntity<CheckChargeResponseDto> response =
                restTemplate.getForEntity(url, CheckChargeResponseDto.class, cancelRequestId);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RiskServiceException("risk-management isCharged 응답 오류: " + response.getStatusCode());
            }
            return response.getBody().charged();
        });
    } catch (RiskServiceException e) {
        throw e;
    } catch (Throwable t) {
        log.error("risk-management isCharged 실패. cancelRequestId={}", cancelRequestId, t);
        throw new RiskServiceException("risk-management 서비스 오류", t);
    }
}

// payment-service 쪽 DTO 신규 필요 (risk-management의 CheckChargeResponse와 필드 동일하게 매핑)
// cancelRequestId는 risk 쪽에서 String이지만, RiskManagementPort 인터페이스는 long — String.valueOf() 변환
public record CheckChargeResponseDto(
    String cancelRequestId, boolean charged, Long merchantId, java.math.BigDecimal cancelAmount
) {}
```

### D-01: `getStatus()` — 관례적 REST 계약 설계 [ASSUMED — 실 PG 문서 없음]

```java
// [ASSUMED] 근거 문서 없음. 기존 cancel() POST 계약(PgCancelHttpClient.java:36-57)과
// PgCancelResult 레코드 구조를 그대로 미러링한 관례적 설계.
// 실 PG 연동 전 checkpoint:human-verify 필수.

@Override
public PgCancelResult getStatus(String paymentKey) {
    try {
        return circuitBreaker.executeCheckedSupplier(() -> {
            // GET {baseUrl}/v1/payments/{paymentKey}/cancel/status
            // 응답 바디: { "pgTransactionId": "...", "status": "APPROVED|FAILED|PENDING", "retryable": bool }
            // (PgCancelResult 레코드와 1:1 매핑 — cancel()의 POST 응답과 동일 스키마)
            String url = baseUrl + "/v1/payments/{paymentKey}/cancel/status";
            ResponseEntity<PgCancelResult> response =
                restTemplate.getForEntity(url, PgCancelResult.class, paymentKey);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PgServiceException("PG 상태조회 응답 오류: " + response.getStatusCode());
            }
            return response.getBody();
        });
    } catch (PgServiceException e) {
        throw e;
    } catch (Throwable t) {
        log.error("PG getStatus 실패. paymentKey={}", paymentKey, t);
        throw new PgServiceException("PG 서비스 오류", t);
        // ★ 이 예외가 ProcessingRecoveryService.recoverOne()의
        //   catch (Exception e) { ... PROCESSING 유지 } 로 흡수됨 (기존 설계 그대로 유효)
    }
}
```
**설계 근거(왜 `paymentKey`만으로 조회하는가):** `CancelRequest` 도메인 엔티티는 `pgTransactionId`를 저장하지 않는다([VERIFIED: `CancelRequest.java` 필드 목록 확인 — 없음]). `cancel()` 호출 시 받은 `pgTransactionId`는 어디에도 영속화되지 않으므로, 복구 시점에 PG와 상관지을 수 있는 유일한 키는 `paymentKey`뿐이다. 이는 `MockPgCancelClient.getStatus(String paymentKey)`와 `PgCancelPort.getStatus(String paymentKey)` 인터페이스 시그니처가 이미 확정한 제약이기도 하다 — 도메인 불변식(취소 기간 내 활성 취소는 최대 1건, `(payment_id, request_hash)` UK)이 "paymentKey당 진행 중 취소는 최대 1건"을 보장하므로 이 설계로 충분하다.

### 원자 UPDATE 전체 흐름 (RESIL-03)

```java
// Source: 코드베이스 패턴 이식 — MerchantCancelUsageJpaRepository.tryRestore (기존, 검증됨)
// [VERIFIED: risk-management-service/.../MerchantCancelUsageJpaRepository.java:51-57]
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
    UPDATE cancel_request
       SET pg_retry_count = pg_retry_count + 1, updated_at = CURRENT_TIMESTAMP(3)
     WHERE id = :id
    """, nativeQuery = true)
int incrementPgRetryCount(@Param("id") long id);
```

## Runtime State Inventory

이 페이즈는 rename/refactor/migration이 아니다(신규 로직 채워넣기) — 이 섹션은 조건상 필요하지 않다. 다만 확인한 결과 상태 관련 우려사항이 없음을 명시한다:

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | 없음 — `CancelRequest`에 `pgTransactionId` 저장 안 함(위 설계 근거 참조), 스키마 변경 불필요 | 없음 |
| Live service config | 없음 — `external.pg.url`, `external.risk-management.url` 기존 설정 그대로 사용 | 없음 |
| OS-registered state | 없음 — 신규 Flyway 마이그레이션 불필요(V13 등 추가 없음, 기존 `pg_retry_count` 컬럼은 V9에서 이미 존재) | 없음 |
| Secrets/env vars | 없음 | 없음 |
| Build artifacts | 없음 — 신규 의존성 없음 | 없음 |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | PG 취소 상태조회 엔드포인트는 `GET {baseUrl}/v1/payments/{paymentKey}/cancel/status`이며 응답이 `PgCancelResult` 스키마(pgTransactionId/status/retryable)와 1:1 매핑된다 | Code Examples §D-01 | **높음(costly, CONTEXT.md D-01 명시)** — 실제 PG가 이 엔드포인트를 제공하지 않거나 응답 스키마가 다르면 `ProcessingRecoveryService.recoverOne()` 전체가 재작업 필요. `checkpoint:human-verify`로 게이트 필수 |
| A2 | `paymentKey`만으로 PG 상태조회가 충분하다(진행 중 취소 건 최대 1개라는 도메인 불변식에 의존) | Code Examples §D-01 근거 | 중간 — 만약 한 paymentKey에 여러 병렬 취소 시도가 PG 레벨에서 허용된다면(현재 도메인 규칙상 불가하지만 향후 부분취소 재도입 시) 상태조회가 모호해질 수 있음 |

## Open Questions

1. **실 PG 상태조회 계약의 인증/타임아웃 정책**
   - What we know: 기존 `cancel()` POST 호출은 `pgCancelCircuitBreaker`(50% 실패율/10초 OPEN)로 감싸져 있고 baseUrl은 `http://pg-gateway:443`(placeholder) [VERIFIED: `application.yml`].
   - What's unclear: `getStatus()` GET 호출에 별도 타임아웃/재시도 정책이 필요한지, 아니면 `cancel()`과 동일 서킷브레이커·타임아웃을 공유해도 되는지.
   - Recommendation: RestTemplate 전역 타임아웃 설정을 그대로 재사용(신규 RestTemplate 빈 만들지 말 것)하고, 같은 `pgCancelCircuitBreaker` 인스턴스를 공유(이미 `PgCancelHttpClient`가 필드로 갖고 있음).

2. **RESIL-03 Testcontainers 테스트에서 Pitfall 1(오탐 로깅)을 어떻게 단언할 것인가**
   - What we know: 동시 `runTx3` 경합의 패자는 `InvalidPaymentItemStatusException`을 던지고 이게 `recoverOne`의 `catch (BusinessException e)`에서 ERROR 로깅된다.
   - What's unclear: 플래너가 이 로그를 "테스트가 캡처해야 할 관찰 가능한 부작용"으로 볼지, 아니면 "무시해도 되는 노이즈"로 볼지는 구현 우선순위 판단이 필요.
   - Recommendation: 최소 요구사항(RESIL-03 성공 기준)은 "정확히 한 번 처리, pg_retry_count 유실 없음"이므로 로그 레벨 정리는 nice-to-have로 플랜에 낮은 우선순위로 포함하거나, 발견 사항으로만 문서화하고 범위에서 제외해도 무방.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker (Testcontainers용) | RESIL-02/03 동시성 통합테스트 | 로컬 환경 확인 필요(researcher는 원격 실행 환경이라 미확인) | — | CI/개발 머신에 Docker 데몬 필수 — 없으면 Testcontainers IT 전체가 skip/fail. `docker compose up -d`가 CLAUDE.md 실행 명령어에 이미 있으므로 이 프로젝트는 Docker 의존을 이미 전제 |
| risk-management-service (로컬 `localhost:8083`) | RESIL-01(D-05) isCharged 배선 검증 | 코드상 설정만 확인, 런타임 기동 여부 미확인 | — | 단위 테스트는 Mockito로 충분(외부 서비스 불필요), 통합 검증 시에만 기동 필요 |
| 실 PG 게이트웨이 | RESIL-01(D-01) getStatus 실제 검증 | 없음(플레이스홀더 `pg-gateway:443`) | — | `MockPgCancelClient`(로컬 프로파일)로 개발/테스트, 실 계약 검증은 `checkpoint:human-verify`로 별도 처리 — 이 페이즈 안에서 실 PG 붙일 수 없음이 이미 알려진 제약 |

**Missing dependencies with no fallback:** 없음.

**Missing dependencies with fallback:** 실 PG 게이트웨이(Mock으로 대체, 계약 자체는 human-verify 필요) — 위 표 참조.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter) + Mockito 4.x + AssertJ + Testcontainers 1.19.7 [VERIFIED: `.planning/codebase/TESTING.md`] |
| Config file | `payment-service/build.gradle`(`useJUnitPlatform()`), `risk-management-service/build.gradle` |
| Quick run command | `./gradlew :payment-service:test --tests "*ProcessingRecoveryServiceTest" --tests "*CancelPaymentServiceTest"` |
| Full suite command | `./gradlew :payment-service:test` (unit + Testcontainers IT 전부) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RESIL-01 | `getStatus()` APPROVED/FAILED/PENDING 매핑 정확성 | unit | `./gradlew :payment-service:test --tests "*PgCancelHttpClientTest"` | ❌ Wave 0 신규 |
| RESIL-01 | `isCharged()` risk 응답 배선 정확성 | unit | `./gradlew :payment-service:test --tests "*RiskManagementHttpClientTest"` | ❓ 기존 클래스에 테스트 추가 필요(존재 여부 planning 시 확인) |
| RESIL-01 | PROCESSING 5분 초과 → TX3 재실행 자동 복구(getStatus 실 스텁 제거 후) | unit(mock 기반) | `./gradlew :payment-service:test --tests "*ProcessingRecoveryServiceTest"` | ✅ 기존 파일에 신규 케이스 추가 |
| RESIL-02 | UK 위반 → 500 아닌 200+status | integration(Testcontainers) | `./gradlew :payment-service:test --tests "*CancelRaceIdempotencyIT"` | ❌ Wave 0 신규 |
| RESIL-02 | 승자/패자 상태 스위치가 api-spec.md §멱등성 응답과 일치 | unit | `./gradlew :payment-service:test --tests "*CancelPaymentServiceTest"` | ✅ 기존 파일에 신규 케이스 추가 |
| RESIL-03 | 동시 스케줄러 실행 시 pg_retry_count 유실 없음(정확히 N회 증가) | integration(Testcontainers, ExecutorService+CountDownLatch) | `./gradlew :payment-service:test --tests "*ProcessingRecoveryConcurrencyIT"` | ❌ Wave 0 신규 |
| RESIL-03 | 동시 실행에도 중복 COMPLETED 없음(정확히 1건) | integration(Testcontainers) | 위와 동일 클래스 | ❌ Wave 0 신규 |

### Sampling Rate

- **Per task commit:** 해당 모듈 단위 테스트만(`--tests` 필터)
- **Per wave merge:** `./gradlew :payment-service:test` 전체(Testcontainers IT 포함, 500ms~수초 소요)
- **Phase gate:** 전체 그린 + `jacocoTestCoverageVerification`(80% 라인 커버리지 기존 게이트 유지) 후 `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java` — RESIL-01 getStatus 매핑 커버
- [ ] `payment-service/src/test/java/com/example/payment/integration/CancelRaceIdempotencyIT.java` — RESIL-02 커버, `AbstractRepositoryTest` 또는 `CancelFlowIntegrationTest` 패턴 재사용
- [ ] `payment-service/src/test/java/com/example/payment/integration/ProcessingRecoveryConcurrencyIT.java` — RESIL-03 커버, `MerchantCancelUsageAtomicDeductIT`의 ExecutorService/CountDownLatch 패턴 이식
- [ ] `RiskManagementHttpClientTest.java` 존재 여부 확인 — 없으면 isCharged 테스트 추가할 파일 신규 생성

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | 내부 서비스 간 HTTP(payment→risk, payment→PG) — 이 페이즈는 기존 인증 방식(있다면) 변경 없음, 신규 추가 안 함 |
| V3 Session Management | no | 해당 없음(비-세션 백엔드 배치/서비스 호출) |
| V4 Access Control | no | `/internal/*` 엔드포인트는 이미 내부망 전용으로 설계됨(기존 컨벤션 유지) — 이 페이즈에서 신규 외부 노출 엔드포인트 없음 |
| V5 Input Validation | yes | `getStatus()`/`isCharged()` 응답 바디의 `status` 필드가 예상 enum(APPROVED/FAILED/PENDING) 밖의 값일 때 `PgCancelResult.isApproved()`류 메서드가 전부 false를 반환하는 안전한 기본값 구조([VERIFIED: 코드 — `PgCancelResult` record의 `"APPROVED".equals(status)` 패턴]) — 신규 검증 코드 불필요, 기존 방어적 설계가 이미 안전 |
| V6 Cryptography | no | 신규 암호화 요구 없음 |

### Known Threat Patterns for {stack}

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| 에러 메시지에 내부 정보 노출(`RiskServiceException`이 HTTP 상태코드를 메시지에 포함) | Information Disclosure | 기존 `.planning/codebase/CONCERNS.md`가 이미 이 문제를 별도 항목으로 추적 중(`RiskServiceException line 51-52`) — Phase 2 신규 코드(`isCharged`/`getStatus` 구현)도 같은 패턴(`log.error` 상세, 예외 메시지는 일반화)을 따르되, `GlobalExceptionHandler`가 이미 500을 `INTERNAL_ERROR`로 일반화하므로 신규 위험 없음. 다만 신규 예외 메시지에 `paymentKey`/`cancelRequestId` 같은 식별자를 로그에는 남기되(운영 추적용) 클라이언트 응답에는 노출하지 않는 기존 원칙을 유지할 것 |
| DoS 유발 재시도 폭주(PG getStatus 무한 재시도) | Denial of Service | 기존 `MAX_PG_RETRIES = 5` 상한 + `resilience4j CircuitBreaker`(50% 실패율 초과 시 10초 OPEN)가 이미 존재 — 신규 로직이 이 상한을 우회하지 않도록 주의(원자 UPDATE로 카운터를 바꿔도 `MAX_PG_RETRIES` 비교 로직 자체는 유지) |

## Sources

### Primary (HIGH confidence — 코드베이스 실측)

- `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java` — getStatus 스텁 현황
- `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java` — isCharged 스텁 현황
- `payment-service/src/main/java/com/example/payment/infrastructure/http/MockPgCancelClient.java` — getStatus 응답 형태 참조
- `payment-service/src/main/java/com/example/payment/application/dto/PgCancelResult.java` — 상태 매핑 타깃 레코드
- `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java` — 복구 상태머신 전체
- `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java` — 멱등성 처리 기존 패턴(FAILED 재시도 분기)
- `payment-service/src/main/java/com/example/payment/application/service/CancelTxWriter.java` — TX 경계, TX3 원자 롤백 확인
- `payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java` — pgRetryCount 필드, 상태 전이 가드
- `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java` — IDENTITY 전략 확인(동기 INSERT 근거)
- `payment-service/src/main/java/com/example/payment/domain/entity/PaymentItem.java` — cancel() 재취소 방지 가드(Pitfall 1 근거)
- `payment-service/src/main/java/com/example/payment/presentation/controller/GlobalExceptionHandler.java` — 현재 UK 위반이 500으로 떨어지는 경로 확인
- `payment-service/src/main/resources/db/migration/V8__align_cancel_schema.sql`, `V9__add_cancel_item_ids_and_pg_retry_count.sql` — UK 제약·pg_retry_count 컬럼 확인
- `risk-management-service/src/main/java/com/example/riskmanagement/presentation/controller/InternalCancelLimitController.java`, `CheckChargeService.java`, `CheckChargeResponse.java` — isCharged 배선 대상 엔드포인트 계약
- `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageJpaRepository.java` — 원자 UPDATE 컨벤션(`tryDeduct`/`tryRestore`)
- `risk-management-service/src/test/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageAtomicDeductIT.java` — Testcontainers 동시성 테스트 패턴
- `payment-service/src/test/java/com/example/payment/infrastructure/persistence/AbstractRepositoryTest.java`, `payment-service/src/test/java/com/example/payment/application/service/ProcessingRecoveryOutboxIT.java` — 기존 IT 컨벤션
- `docs/api-spec.md` §멱등성 처리 응답(라인 107-115) — 200+status 계약
- `.planning/codebase/CONCERNS.md` — RESIL-01/03 결함의 코드베이스 관점 사전 문서화, D-04(레코드 락) 대안 검토용
- `docs/superpowers/specs/2026-04-28-scheduler-enhancement-design.md` — 복구 상태머신 설계 원본, 인터페이스 시그니처 근거
- `sysdesign/cancel-design.md` §8(스케줄러) — pending/processing-recovery 흐름 서술(단, Outbox/AFTER_COMMIT 대안 설계 서술은 main 브랜치와 무관 — 문서 상단 경고 확인)

### Secondary (MEDIUM confidence)

- `docs/load-test/k3s-scaleout-results.md` 실험②(라인 13, 32-34) — RESIL-02 결함의 실측 근거(멀티파드 동시 취소 패자 500)

### Tertiary (LOW confidence — ASSUMED)

- D-01 PG 상태조회 REST 계약(`GET /v1/payments/{paymentKey}/cancel/status`) — 근거 문서 없음, 기존 `cancel()` POST 계약을 미러링한 관례적 설계. `checkpoint:human-verify` 필수.

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH — 신규 라이브러리 없음, 전부 기존 코드베이스 의존성 재사용
- Architecture: HIGH — RESIL-02/03은 기존 코드의 자매 패턴을 실측 확인 후 그대로 이식. RESIL-01(D-05)도 기존 엔드포인트 실측 확인
- D-01(PG 상태조회 계약): LOW — 근거 문서 부재, ASSUMED 설계. CONTEXT.md가 이미 costly reversibility로 flag
- Pitfalls: HIGH — 코드 정독으로 실제 예외 전파 경로(IDENTITY 동기 INSERT, InvalidPaymentItemStatusException 오탐 로깅, stale in-memory 카운터)를 직접 추적 확인

**Research date:** 2026-07-28
**Valid until:** 코드 구조 자체는 변경 잦지 않으므로 60일(내부 리팩토링 기준). 단, D-01(PG 계약)은 실 PG 문서 확보 시 즉시 무효화 — 그 즉시 갱신 필요.
