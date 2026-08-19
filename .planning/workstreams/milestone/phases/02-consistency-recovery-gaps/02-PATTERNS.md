# Phase 2: 정합성 & 복구 갭 마감 - Pattern Map

**Mapped:** 2026-07-28
**Files analyzed:** 8 (수정 6, 신규 2 테스트 클래스 그룹)
**Analogs found:** 8 / 8 (전부 같은 모듈/자매 모듈 내부에 정확한 자매 패턴 존재 — RESEARCH.md가 이미 코드 정독 완료)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `payment-service/.../infrastructure/http/PgCancelHttpClient.java` (`getStatus`) | service (HTTP client/adapter) | request-response | 같은 파일의 `cancel()` (lines 36-57) | exact (같은 클래스, 같은 CircuitBreaker/RestTemplate 패턴) |
| `payment-service/.../infrastructure/http/RiskManagementHttpClient.java` (`isCharged`) | service (HTTP client/adapter) | request-response | 같은 파일의 `validateAndReserve()` (lines 36-61) | exact |
| `payment-service/.../application/service/CancelPaymentService.java` (`executeCancel` — UK catch 추가) | service (usecase) | CRUD (트랜잭션 쓰기) | 같은 파일의 `handleExistingRequest()` FAILED 분기 (lines 66-80) | exact (동일 파일, 동일 "UK 충돌→재조회→상태 스위치" 개념) |
| `payment-service/.../application/service/ProcessingRecoveryService.java` (`retryPgCancel` — 원자 UPDATE 교체) | service (스케줄러 복구 로직) | batch / event-driven | `risk-management-service/.../MerchantCancelUsageJpaRepository.tryDeduct/tryRestore` | role-match (다른 모듈이지만 동일 "원자 UPDATE" 컨벤션의 원조) |
| `payment-service/.../application/interfaces/CancelRequestRepository.java` (`incrementPgRetryCount` 추가) | interface (repository port) | CRUD | 같은 파일의 기존 메서드 시그니처 (전체) | exact |
| `payment-service/.../infrastructure/persistence/CancelRequestJpaRepository.java` (`incrementPgRetryCount` 추가) | repository (Spring Data JPA) | CRUD | `MerchantCancelUsageJpaRepository.tryDeduct` (lines 37-44) | exact (원자 UPDATE 컨벤션의 원조) |
| `payment-service/.../infrastructure/persistence/CancelRequestRepositoryImpl.java` (위임 메서드 추가) | repository (adapter/delegate) | CRUD | 같은 파일의 기존 위임 메서드 (전체) | exact |
| `payment-service/src/test/.../infrastructure/http/PgCancelHttpClientTest.java` (신규) | test (unit) | request-response | 없음 — 기존 `cancel()`용 단위 테스트 파일이 있으면 그 구조 참조(파일 미확인, RESEARCH.md도 존재 여부 미확인 표시) | no analog found (아래 참조) |
| `payment-service/src/test/.../integration/CancelRaceIdempotencyIT.java` (신규) | test (integration, Testcontainers) | CRUD/concurrency | `payment-service/.../AbstractRepositoryTest.java` 상속 구조 + risk-management의 동시성 IT 스타일 | role-match |
| `payment-service/src/test/.../integration/ProcessingRecoveryConcurrencyIT.java` (신규) | test (integration, Testcontainers) | batch/concurrency | `risk-management-service/.../MerchantCancelUsageAtomicDeductIT.java` (전체) | exact (ExecutorService+CountDownLatch 동시성 패턴 원조) |

## Pattern Assignments

### `PgCancelHttpClient.getStatus()` (service/HTTP adapter, request-response)

**Analog:** 같은 파일의 `cancel()` — `payment-service/src/main/java/com/example/payment/infrastructure/http/PgCancelHttpClient.java:36-57`

**Imports** (lines 1-14): 이미 파일에 존재 — 추가 import 불필요(`PgCancelResult`, `PgServiceException`, `CircuitBreaker`, `RestTemplate` 전부 이미 임포트됨).

**Core 패턴** (`cancel()` lines 36-57, 그대로 GET으로 미러링):
```java
@Override
public PgCancelResult cancel(String paymentKey, BigDecimal cancelAmount, String cancelReason) {
    try {
        return circuitBreaker.executeCheckedSupplier(() -> {
            String url = baseUrl + "/v1/payments/{paymentKey}/cancel";
            Map<String, Object> request = Map.of(
                "cancelAmount", cancelAmount,
                "cancelReason", cancelReason
            );
            ResponseEntity<PgCancelResult> response =
                restTemplate.postForEntity(url, request, PgCancelResult.class, paymentKey);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PgServiceException("PG 취소 응답 오류: " + response.getStatusCode());
            }
            return response.getBody();
        });
    } catch (PgServiceException e) {
        throw e;
    } catch (Throwable t) {
        log.error("PG cancel 실패. paymentKey={}", paymentKey, t);
        throw new PgServiceException("PG 서비스 오류", t);
    }
}
```

**현재 스텁** (교체 대상, lines 59-63):
```java
@Override
public PgCancelResult getStatus(String paymentKey) {
    // TODO: implement in Task 6
    throw new UnsupportedOperationException("getStatus not yet implemented");
}
```

**적용:** 위 `cancel()` 구조를 `restTemplate.getForEntity(url, PgCancelResult.class, paymentKey)`로 바꾸고 URL을 `/v1/payments/{paymentKey}/cancel/status`로 변경(RESEARCH.md D-01 `[ASSUMED]` 계약 — `checkpoint:human-verify` 필수). `circuitBreaker` 필드(생성자에서 `pgCancelCircuitBreaker` 주입, line 28)는 재사용 — 신규 CircuitBreaker 빈 만들지 말 것.

---

### `RiskManagementHttpClient.isCharged()` (service/HTTP adapter, request-response)

**Analog:** 같은 파일의 `validateAndReserve()` — `payment-service/src/main/java/com/example/payment/infrastructure/http/RiskManagementHttpClient.java:36-61`

**Core 패턴** (POST → GET 미러링):
```java
@Override
public RiskReserveResult validateAndReserve(
    long merchantId, long cancelRequestId, BigDecimal cancelAmount, LocalDate kstDate
) {
    try {
        return circuitBreaker.executeCheckedSupplier(() -> {
            String url = baseUrl + "/internal/cancel-limit/validate-and-reserve";
            Map<String, Object> request = Map.of(
                "merchantId", merchantId,
                "cancelRequestId", String.valueOf(cancelRequestId),
                "cancelAmount", cancelAmount,
                "kstDate", kstDate.toString()
            );
            ResponseEntity<RiskReserveResult> response =
                restTemplate.postForEntity(url, request, RiskReserveResult.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RiskServiceException("risk-management 응답 오류: " + response.getStatusCode());
            }
            return response.getBody();
        });
    } catch (RiskServiceException e) {
        throw e;
    } catch (Throwable t) {
        log.error("risk-management validateAndReserve 실패. merchantId={}", merchantId, t);
        throw new RiskServiceException("risk-management 서비스 오류", t);
    }
}
```

**현재 스텁** (교체 대상, lines 81-85):
```java
@Override
public boolean isCharged(long cancelRequestId) {
    // TODO: implement in Task 6
    throw new UnsupportedOperationException("isCharged not yet implemented");
}
```

**적용:** RESEARCH.md D-05가 이미 완성된 구현체를 제공(`/internal/cancel-limit/check?cancelRequestId={id}` GET, `CheckChargeResponseDto(cancelRequestId, charged, merchantId, cancelAmount)` 신규 DTO record 필요 — `application/dto` 패키지에 `RiskReserveResult` 옆에 추가). 엔드포인트는 신규 설계 아님 — `risk-management-service`의 기존 `InternalCancelLimitController`/`CheckChargeUseCase`에 배선만.

---

### `CancelPaymentService.executeCancel` — UK 위반 catch 추가 (service, CRUD)

**Analog:** 같은 파일의 `handleExistingRequest()` — `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java:66-80`

**기존 "UK 충돌류 재조회→상태 스위치" 패턴** (FAILED 재시도 분기, lines 71-79):
```java
return switch (cancelRequest.getStatus()) {
    case COMPLETED, PENDING, PROCESSING -> cancelRequest;
    case FAILED -> {
        cancelRequest.raiseToPending();
        cancelRequestRepository.save(cancelRequest);
        recordHistory(cancelRequest.getId(), CancelStatus.PENDING, "FAILED 재시도");
        yield executeCancel(payment, items, cancelRequest.getRequestHash(), command);
    }
};
```

**TX1 호출 지점** (catch 삽입 위치, lines 91-97):
```java
// TX1: CancelRequest PENDING INSERT
BigDecimal cancelAmount = calculateCancelAmount(items, command.cancelPaymentItemIds());
CancelRequest cancelRequest = CancelRequest.create(
    payment.getId(), requestHash, cancelAmount, command.cancelReason(),
    command.cancelPaymentItemIds());
cancelRequest = cancelTxWriter.saveTx1(cancelRequest);   // ★ 여기를 try/catch로 감싼다
recordHistory(cancelRequest.getId(), CancelStatus.PENDING, null);
```

**적용** (RESEARCH.md Code Examples Pattern 1 — 그대로 채택):
```java
try {
    cancelRequest = cancelTxWriter.saveTx1(cancelRequest);
} catch (org.springframework.dao.DataIntegrityViolationException e) {
    CancelRequest winner = cancelRequestRepository
        .findByPaymentIdAndRequestHash(payment.getId(), requestHash)
        .orElseThrow(() -> e);
    return handleExistingRequest(winner, command, payment, items);
}
```
`handleExistingRequest`(lines 66-80)를 그대로 재사용 — 새 응답 형태 만들지 않는다(D-03). `CancelRequestJpaEntity`가 `IDENTITY` 전략이라 `saveTx1()` 호출 시점에 동기적으로 예외가 던져짐(RESEARCH.md 확인).

---

### `ProcessingRecoveryService.retryPgCancel` — 원자 UPDATE 교체 (service, batch/event-driven)

**Analog:** `risk-management-service/src/main/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageJpaRepository.java:37-44` (`tryDeduct`)

**원자 UPDATE 컨벤션** (analog, lines 37-44):
```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
    UPDATE merchant_cancel_usage
       SET used_amount = used_amount + :amount, updated_at = CURRENT_TIMESTAMP(6)
     WHERE merchant_id = :merchantId AND kst_date = :kstDate
       AND used_amount + :amount <= daily_limit
    """, nativeQuery = true)
int tryDeduct(long merchantId, LocalDate kstDate, BigDecimal amount);
```

**현재 read-modify-write 결함** (`ProcessingRecoveryService.java:94-96`, 교체 대상):
```java
private void retryPgCancel(CancelRequest cancelRequest, Payment payment) {
    cancelRequest.incrementPgRetryCount();
    cancelRequestRepository.save(cancelRequest);
    ...
```

**적용:**
1. `CancelRequestJpaRepository`(analog와 같은 계층)에 신규 메서드 추가:
```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(value = """
    UPDATE cancel_request
       SET pg_retry_count = pg_retry_count + 1, updated_at = CURRENT_TIMESTAMP(3)
     WHERE id = :id
    """, nativeQuery = true)
int incrementPgRetryCount(@Param("id") long id);
```
2. `CancelRequestRepository`(interface) + `CancelRequestRepositoryImpl`(delegate)에 위임 추가 — `CancelRequestRepositoryImpl.java`의 기존 위임 메서드(예: `findPendingCreatedBefore`, lines 34-39) 스타일 그대로.
3. `retryPgCancel`에서 mutation+save 제거 후 원자 UPDATE 호출 + **재조회 필수**(RESEARCH.md Pitfall 2 — 로컬 `cancelRequest` 객체는 stale, 임계값 `MAX_PG_RETRIES` 비교 직전 반드시 `findByPaymentIdAndRequestHash`로 재조회).

---

### 신규 테스트: `ProcessingRecoveryConcurrencyIT.java` (RESIL-03, integration/Testcontainers)

**Analog (그대로 이식):** `risk-management-service/src/test/java/com/example/riskmanagement/infrastructure/persistence/MerchantCancelUsageAtomicDeductIT.java` (전체, 특히 lines 35-64)

**ExecutorService + CountDownLatch 동시성 패턴** (핵심 발췌, lines 45-59):
```java
ExecutorService pool = Executors.newFixedThreadPool(threads);
CountDownLatch start = new CountDownLatch(1);
AtomicInteger success = new AtomicInteger();
List<Future<?>> futures = new ArrayList<>();
for (int i = 0; i < threads; i++) {
    futures.add(pool.submit(() -> {
        start.await();
        Integer r = tx.execute(s -> repo.tryDeduct(m, TODAY, amt)); // 각 호출 독립 TX
        if (r != null && r == 1) success.incrementAndGet();
        return null;
    }));
}
start.countDown();
for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
pool.shutdown();
```
**적용:** `pg_retry_count` 원자 UPDATE를 N개 스레드가 동시 호출 → 최종 카운트가 정확히 N인지 검증(`assertThat(...).isEqualTo(...)`). `TransactionTemplate` 주입 스타일도 그대로(각 호출 독립 TX). 테스트 클래스는 `AbstractRepositoryTest`를 상속(payment-service 자체 버전: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/AbstractRepositoryTest.java`).

**RESIL-02용 `CancelRaceIdempotencyIT.java`도 동일 골격 재사용** — 스레드 수는 2(승자/패자)로 줄이고, 검증 포인트는 "정확히 1건 INSERT, 나머지는 200 멱등 응답"(RESEARCH.md Pitfall 1 참조 — 패자의 `InvalidPaymentItemStatusException`은 정상, ERROR 로깅 오탐이지 테스트 실패 조건 아님).

---

## Shared Patterns

### HTTP 클라이언트 어댑터 (Port/Adapter + CircuitBreaker)
**Source:** `PgCancelHttpClient.java` / `RiskManagementHttpClient.java` (파일 전체)
**Apply to:** `getStatus()`, `isCharged()` 둘 다
```java
try {
    return circuitBreaker.executeCheckedSupplier(() -> {
        // RestTemplate 호출 + 2xx/null 체크 → throw XxxServiceException
    });
} catch (XxxServiceException e) {
    throw e;
} catch (Throwable t) {
    log.error("... 실패. key={}", key, t);
    throw new XxxServiceException("... 서비스 오류", t);
}
```
신규 CircuitBreaker/RestTemplate 빈 금지 — 생성자 주입된 기존 필드(`circuitBreaker`, `restTemplate`, `baseUrl`) 그대로 재사용.

### 원자 UPDATE (`@Modifying @Query` + `clearAutomatically = true`)
**Source:** `MerchantCancelUsageJpaRepository.java:37-57` (`tryDeduct`/`tryRestore`)
**Apply to:** `CancelRequestJpaRepository.incrementPgRetryCount`
- 단일 SQL 문장 → InnoDB 행 락으로 원자성 보장, 애플리케이션 레벨 락 불필요.
- `clearAutomatically = true` 없으면 같은 TX 내 재조회가 1차 캐시의 stale 값을 봄 — 반드시 포함.
- 호출자의 로컬 도메인 객체는 자동 갱신 안 됨 → 임계값 비교 전 재조회 필수(Pitfall 2).

### UK 위반 → 멱등 응답 번역
**Source:** `CancelPaymentService.handleExistingRequest()` (lines 66-80)
**Apply to:** `executeCancel`의 `saveTx1` catch 지점만 — 전역 `@ExceptionHandler`로 만들지 말 것(다른 UK 제약과 의미 오염, RESEARCH.md Anti-Patterns).

### Testcontainers 동시성 재현
**Source:** `MerchantCancelUsageAtomicDeductIT.java` (전체)
**Apply to:** `CancelRaceIdempotencyIT`, `ProcessingRecoveryConcurrencyIT`
- `AbstractRepositoryTest` 상속 + 실 MySQL(Testcontainers)
- `ExecutorService` + `CountDownLatch`(start 신호로 동시 착지 강제) + `Future.get(timeout)` 수거
- 검증은 "최종 DB 상태"만(정확히 N건/N회) — 중간 예외(레이스 패자의 정상 실패)는 검증 대상 아님

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `payment-service/src/test/java/com/example/payment/infrastructure/http/PgCancelHttpClientTest.java` | test (unit) | request-response | 기존 `cancel()`용 단위 테스트 파일 존재 여부 미확인(RESEARCH.md도 "❌ Wave 0 신규"로 표시). Mockito로 `RestTemplate`/`CircuitBreaker` 목업 — 프로젝트 표준 Mockito 컨벤션(JUnit 5 + Mockito 4.x)을 그대로 따르되 별도 analog 파일 지정 없이 신규 작성. |
| `RiskManagementHttpClientTest.java` | test (unit) | request-response | 존재 여부 planning 시 확인 필요(RESEARCH.md 명시) — 있으면 기존 파일에 `isCharged` 케이스 추가, 없으면 위와 동일하게 신규. |

## Metadata

**Analog search scope:** `payment-service/src/main/java/com/example/payment/{infrastructure/http,application/service,application/interfaces,infrastructure/persistence}`, `risk-management-service/src/main/java/.../infrastructure/persistence`, `risk-management-service/src/test/.../infrastructure/persistence`
**Files scanned:** 10 (본문 8 + 테스트 analog 2)
**Pattern extraction date:** 2026-07-28
**참고:** 이 페이즈는 RESEARCH.md가 이미 코드 정독 기반 concrete 발췌를 다수 포함 — 본 문서는 RESEARCH.md 발췌를 실측 재확인(라인 번호 검증)하고 플래너가 바로 쓸 수 있는 "파일별 analog 배정" 형태로 재구성한 것.
