# Cancel Outbox Redrive Request Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영자가 DEAD 취소 outbox에 대해 중복 없는 durable `REQUESTED` redrive 작업을 생성하고 전체 감사 상태를 조회할 수 있게 한다.

**Architecture:** payment-service 안에 redrive 도메인과 application port/use case를 추가하고, 기본 payment `DataSource`를 사용하는 JDBC adapter가 원본 outbox 행 잠금부터 충돌 판정과 insert까지 한 트랜잭션에서 처리한다. HTTP 경계는 기존 `InternalOperatorAccess`를 재사용하며 작업 생성은 downstream 검사나 Kafka replay에 의존하지 않는다.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC, Spring JDBC/JPA transaction manager, Flyway, MySQL 8 Testcontainers, JUnit 5, AssertJ, Mockito, MockMvc

## Global Constraints

- 원본 `cancel_event_outbox`는 작업 생성 전후 `DEAD`를 유지한다.
- 사유는 `trim()` 결과가 비어 있으면 거부하고 입력 원문의 Unicode 코드 포인트 수가 500 이하여야 한다.
- 유효한 사유는 앞뒤 공백을 포함한 입력 원문 그대로 저장한다.
- 동일 원본에는 `REQUESTED` 또는 `REDRIVING` 작업을 하나만 허용한다.
- `RESOLVED` 또는 `RESOLVED_ALREADY_APPLIED` 이력이 있으면 새 요청을 거부한다.
- `FAILED` 또는 `REJECTED` 이력 뒤에는 새 요청을 허용한다.
- POST는 500ms 이내 작업 저장만 마치고 `202 Accepted`를 반환하며 downstream 검사와 Kafka replay를 호출하지 않는다.
- 작업 조회는 상태, 실패 단계, 요청자, 원문 사유, 요청·시작·완료 시각, 결과·오류, 전후 상태를 반환한다.

---

## File Map

### Create

- `payment-service/src/main/resources/db/migration/V21__create_cancel_outbox_redrive.sql`: redrive 감사 테이블, 상태 제약, 활성 작업 unique index.
- `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedrive.java`: 작업 불변식과 상태 전이.
- `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveStatus.java`: 전체 작업 상태.
- `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveFailureStage.java`: 실패 단계.
- `payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java`: 원자적 생성과 조회 port.
- `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java`: 기본 payment DB JDBC adapter.
- `payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveUseCase.java`: 생성 use case.
- `payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveQuery.java`: 조회 use case.
- `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveService.java`: 사유 정책과 생성·조회 orchestration.
- `payment-service/src/main/java/com/example/payment/application/exception/ActiveRedriveExistsException.java`: 활성 중복 409.
- `payment-service/src/main/java/com/example/payment/application/exception/RedriveAlreadyResolvedException.java`: 해결 완료 409.
- `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxRedriveNotFoundException.java`: 작업 조회 404.
- `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxNotDeadException.java`: DEAD 아닌 원본 409.
- `payment-service/src/main/java/com/example/payment/application/exception/InvalidRedriveReasonException.java`: 사유 400.
- `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveRequest.java`: POST body.
- `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveResponse.java`: POST/GET 응답.
- `payment-service/src/test/java/com/example/payment/domain/entity/CancelOutboxRedriveTest.java`: 상태 전이와 불변식.
- `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java`: migration과 round-trip.
- `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java`: 동일 원본 동시 생성.
- `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveServiceTest.java`: 사유와 use case 경계.

### Modify

- `payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java`: 기본 `DataSource` 기반 repository bean.
- `payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`: 신규 400/404/409 코드.
- `payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java`: POST와 redrive GET endpoint.
- `payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java`: 인증, validation, 응답 계약, 오류 매핑.

---

### Task 1: Redrive Schema and Domain State Machine

**Files:**

- Create: `payment-service/src/main/resources/db/migration/V21__create_cancel_outbox_redrive.sql`
- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveStatus.java`
- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveFailureStage.java`
- Create: `payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedrive.java`
- Test: `payment-service/src/test/java/com/example/payment/domain/entity/CancelOutboxRedriveTest.java`

**Interfaces:**

- Produces: `CancelOutboxRedrive.requested(long, String, String, Instant)` and `CancelOutboxRedrive.reconstitute(...)`.
- Produces: `start(Instant)`, `resolve(String, String, Instant)`, `resolveAlreadyApplied(String, String, Instant)`, `reject(String, String, String, Instant)`, `fail(CancelOutboxRedriveFailureStage, String, String, Instant)`.
- Produces: getters for all persisted fields; JSON values remain nullable `String` values at this boundary.

- [ ] **Step 1: Write failing domain tests**

Create tests that prove initial state, raw reason preservation, allowed transitions, terminal-state rejection, and failure-stage invariants. Use a fixed instant and assertions shaped like:

```java
@Test
void requestedPreservesAuditInputAndStartsWithoutExecutionFields() {
    Instant requestedAt = Instant.parse("2026-08-11T00:00:00Z");

    var redrive = CancelOutboxRedrive.requested(
        41L, "operator-1", "  Kafka 장애 복구  ", requestedAt);

    assertThat(redrive.getSourceOutboxId()).isEqualTo(41L);
    assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
    assertThat(redrive.getReason()).isEqualTo("  Kafka 장애 복구  ");
    assertThat(redrive.getRequestedAt()).isEqualTo(requestedAt);
    assertThat(redrive.getFailureStage()).isNull();
    assertThat(redrive.getStartedAt()).isNull();
    assertThat(redrive.getCompletedAt()).isNull();
}

@Test
void failedRequiresFailureStageAndTerminalStateCannotTransitionAgain() {
    var redrive = CancelOutboxRedrive.requested(
        41L, "operator-1", "복구", Instant.parse("2026-08-11T00:00:00Z"));
    redrive.start(Instant.parse("2026-08-11T00:00:01Z"));
    redrive.fail(CancelOutboxRedriveFailureStage.PUBLISH,
        "broker timeout", "{\"order\":\"NOT_APPLIED\"}",
        Instant.parse("2026-08-11T00:00:02Z"));

    assertThat(redrive.getStatus()).isEqualTo(CancelOutboxRedriveStatus.FAILED);
    assertThat(redrive.getFailureStage()).isEqualTo(CancelOutboxRedriveFailureStage.PUBLISH);
    assertThatThrownBy(() -> redrive.start(Instant.parse("2026-08-11T00:00:03Z")))
        .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the domain test and verify RED**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveTest'`

Expected: compilation failure because the redrive domain types do not exist.

- [ ] **Step 3: Implement the minimal domain model**

Use explicit enum values:

```java
public enum CancelOutboxRedriveStatus {
    REQUESTED, REDRIVING, RESOLVED, RESOLVED_ALREADY_APPLIED, REJECTED, FAILED;

    public boolean isActive() {
        return this == REQUESTED || this == REDRIVING;
    }

    public boolean isResolved() {
        return this == RESOLVED || this == RESOLVED_ALREADY_APPLIED;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
```

Implement `CancelOutboxRedrive` as a focused mutable aggregate. `start` only accepts `REQUESTED`; all terminal methods only accept `REDRIVING`; `fail` rejects null failure stages; every non-FAILED transition clears `failureStage`; terminal methods set `completedAt`. Do not validate the HTTP reason policy in the aggregate because reconstitution must accept persisted audit text unchanged.

- [ ] **Step 4: Add the V21 migration**

Use this schema shape:

```sql
CREATE TABLE cancel_outbox_redrive (
  id                       BIGINT       NOT NULL AUTO_INCREMENT,
  source_outbox_id         BIGINT       NOT NULL,
  status                   VARCHAR(32)  NOT NULL,
  failure_stage            VARCHAR(20)  NULL,
  requested_by             VARCHAR(255) NOT NULL,
  reason                   VARCHAR(500) NOT NULL,
  requested_at             DATETIME(6)  NOT NULL,
  started_at               DATETIME(6)  NULL,
  completed_at             DATETIME(6)  NULL,
  result                   JSON         NULL,
  last_error               VARCHAR(500) NULL,
  before_state             JSON         NULL,
  after_state              JSON         NULL,
  active_source_outbox_id  BIGINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('REQUESTED', 'REDRIVING') THEN source_outbox_id ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  KEY idx_cancel_outbox_redrive_source (source_outbox_id),
  UNIQUE KEY uk_cancel_outbox_redrive_active (active_source_outbox_id),
  CONSTRAINT fk_cancel_outbox_redrive_source
    FOREIGN KEY (source_outbox_id) REFERENCES cancel_event_outbox(id),
  CONSTRAINT chk_cancel_outbox_redrive_status CHECK (
    status IN ('REQUESTED', 'REDRIVING', 'RESOLVED',
               'RESOLVED_ALREADY_APPLIED', 'REJECTED', 'FAILED')
  ),
  CONSTRAINT chk_cancel_outbox_redrive_failure_stage CHECK (
    (status = 'FAILED' AND failure_stage IN ('PUBLISH', 'CONVERGENCE')) OR
    (status <> 'FAILED' AND failure_stage IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 5: Run the domain test and verify GREEN**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveTest'`

Expected: PASS with no warning or unexpected exception output.

- [ ] **Step 6: Commit the domain and schema slice**

```bash
git add payment-service/src/main/resources/db/migration/V21__create_cancel_outbox_redrive.sql payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedrive.java payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveStatus.java payment-service/src/main/java/com/example/payment/domain/entity/CancelOutboxRedriveFailureStage.java payment-service/src/test/java/com/example/payment/domain/entity/CancelOutboxRedriveTest.java
git commit -m "feat(payment): model cancel outbox redrive jobs"
```

---

### Task 2: Atomic JDBC Repository and Concurrency Guard

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java`
- Create: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/ActiveRedriveExistsException.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/RedriveAlreadyResolvedException.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxNotDeadException.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java`
- Modify: `payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java`

**Interfaces:**

- Consumes: Task 1 `CancelOutboxRedrive` and status enums.
- Produces: `CancelOutboxRedrive createRequested(long sourceOutboxId, String requestedBy, String reason, Instant requestedAt)`.
- Produces: `Optional<CancelOutboxRedrive> findById(long redriveId)`.

- [ ] **Step 1: Write failing repository round-trip and concurrency tests**

Seed a cancelled payment, completed cancel request, and DEAD outbox as `CancelOutboxSourceAdapterIT` does. Assert that `createRequested` returns a generated ID, preserves raw reason, persists every nullable audit column, and leaves the source status unchanged:

```java
var created = repository.createRequested(
    outboxId, "operator-1", "  장애 복구  ", requestedAt);
var loaded = repository.findById(created.getId()).orElseThrow();

assertThat(loaded.getStatus()).isEqualTo(CancelOutboxRedriveStatus.REQUESTED);
assertThat(loaded.getReason()).isEqualTo("  장애 복구  ");
assertThat(loaded.getRequestedAt()).isEqualTo(requestedAt);
assertThat(loaded.getFailureStage()).isNull();
assertThat(jdbc.queryForObject(
    "SELECT status FROM cancel_event_outbox WHERE id = ?", String.class, outboxId))
    .isEqualTo("DEAD");
```

Also seed prior terminal rows directly and prove `RESOLVED`/`RESOLVED_ALREADY_APPLIED` throw `RedriveAlreadyResolvedException`, while `FAILED`/`REJECTED` permit a fresh `REQUESTED` row.

Create `CancelOutboxRedriveRepositoryConcurrencyIT` at the same time. Annotate its test method with `@Transactional(propagation = Propagation.NOT_SUPPORTED)`, commit fixture setup before starting threads, use two executor tasks and a `CountDownLatch` start gate, and call a `TransactionTemplate` around each `createRequested` call. Assert one success, one `ActiveRedriveExistsException`, and one active row:

```java
assertThat(results).filteredOn(Result::created).hasSize(1);
assertThat(results).filteredOn(r -> r.error() instanceof ActiveRedriveExistsException)
    .hasSize(1);
assertThat(jdbc.queryForObject("""
    SELECT COUNT(*) FROM cancel_outbox_redrive
     WHERE source_outbox_id = ? AND status IN ('REQUESTED', 'REDRIVING')
    """, Integer.class, outboxId)).isEqualTo(1);
```

- [ ] **Step 2: Run repository tests and verify RED**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveRepository*'`

Expected: compilation failure because the repository port and adapter do not exist.

- [ ] **Step 3: Implement the repository port and JDBC adapter**

In `createRequested`, execute these operations in one caller transaction:

```sql
SELECT status
  FROM cancel_event_outbox
 WHERE id = :sourceOutboxId
 FOR UPDATE
```

If no row exists, throw the existing `CancelOutboxNotFoundException`; if the status is not `DEAD`, throw `CancelOutboxNotDeadException`. Then query:

```sql
SELECT status
  FROM cancel_outbox_redrive
 WHERE source_outbox_id = :sourceOutboxId
 ORDER BY id DESC
```

Reject any active status before checking resolved statuses. Insert the `REQUESTED` row with a `GeneratedKeyHolder`, then select it through the same row mapper and return it. Catch only duplicate-key violations for `uk_cancel_outbox_redrive_active` and map them to `ActiveRedriveExistsException`; let unrelated integrity failures propagate.

Register the adapter using the primary payment `DataSource`, not `cancelOutboxJdbcTemplate`:

```java
@Bean
public CancelOutboxRedriveRepository cancelOutboxRedriveRepository(
    @Qualifier("dataSource") DataSource dataSource
) {
    return new CancelOutboxRedriveRepositoryImpl(
        new NamedParameterJdbcTemplate(dataSource));
}
```

Add `CANCEL_OUTBOX_NOT_DEAD`, `ACTIVE_REDRIVE_EXISTS`, and `REDRIVE_ALREADY_RESOLVED` to `ErrorCode`, all with HTTP 409.

- [ ] **Step 4: Run repository tests and verify GREEN repeatedly**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveRepository*' --rerun-tasks`

Run the same command three times. Expected every run: PASS, including Flyway V21 migration, source outbox immutability, one created row, one stable active-conflict exception, and one active DB row.

- [ ] **Step 5: Commit the repository slice**

```bash
git add payment-service/src/main/java/com/example/payment/application/interfaces/CancelOutboxRedriveRepository.java payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryImpl.java payment-service/src/main/java/com/example/payment/application/exception/ActiveRedriveExistsException.java payment-service/src/main/java/com/example/payment/application/exception/RedriveAlreadyResolvedException.java payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxNotDeadException.java payment-service/src/main/java/com/example/payment/infrastructure/config/PersistenceConfig.java payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryIT.java payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelOutboxRedriveRepositoryConcurrencyIT.java
git commit -m "feat(payment): persist cancel outbox redrive requests"
```

---

### Task 3: Create and Query Use Cases

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveUseCase.java`
- Create: `payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveQuery.java`
- Create: `payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveService.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxRedriveNotFoundException.java`
- Create: `payment-service/src/main/java/com/example/payment/application/exception/InvalidRedriveReasonException.java`
- Modify: `payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java`
- Test: `payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveServiceTest.java`

**Interfaces:**

- Consumes: `CancelOutboxRedriveRepository.createRequested(...)` and `findById(...)` from Task 2.
- Produces: `CancelOutboxRedrive request(long outboxId, String requestedBy, String reason)`.
- Produces: `CancelOutboxRedrive get(long redriveId)`.

- [ ] **Step 1: Write failing service tests**

Use a mocked repository and fixed `Clock`. Cover null, ASCII whitespace, empty, 501 Unicode code points, a valid 500-code-point string containing supplementary characters, raw reason preservation, and missing query ID:

```java
@ParameterizedTest
@NullSource
@ValueSource(strings = {"", " ", "\t\n"})
void invalidReasonCreatesNoRow(String reason) {
    assertThatThrownBy(() -> service.request(41L, "operator-1", reason))
        .isInstanceOf(InvalidRedriveReasonException.class);
    verifyNoInteractions(repository);
}

@Test
void validReasonUsesCodePointLengthAndPreservesOriginalText() {
    String reason = "😀".repeat(499) + " ";
    Instant now = Instant.parse("2026-08-11T00:00:00Z");
    when(repository.createRequested(41L, "operator-1", reason, now))
        .thenReturn(CancelOutboxRedrive.requested(41L, "operator-1", reason, now));

    var result = service.request(41L, "operator-1", reason);

    assertThat(result.getReason()).isEqualTo(reason);
    verify(repository).createRequested(41L, "operator-1", reason, now);
}
```

Add a boundary test that a 501-code-point input calls no repository method. Query absence must throw `CancelOutboxRedriveNotFoundException`.

- [ ] **Step 2: Run service tests and verify RED**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveServiceTest'`

Expected: compilation failure because use cases and service do not exist.

- [ ] **Step 3: Implement minimal use cases and service**

Define separate command and query interfaces. Validate with:

```java
private static void validateReason(String reason) {
    if (reason == null || reason.trim().isEmpty()
        || reason.codePointCount(0, reason.length()) > 500) {
        throw new InvalidRedriveReasonException();
    }
}
```

Annotate `request` with `@Transactional` so the JDBC adapter's lock, conflict query, and insert share the primary transaction. Annotate `get` with `@Transactional(readOnly = true)`. Use injected `Clock` for `requestedAt`; pass the untouched reason to the repository.

Add `CANCEL_OUTBOX_REDRIVE_NOT_FOUND` as 404. `InvalidRedriveReasonException` uses existing `INVALID_REQUEST`.

- [ ] **Step 4: Run service tests and verify GREEN**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveServiceTest'`

Expected: PASS, with repository untouched for every invalid reason.

- [ ] **Step 5: Run repository and service slices together**

Run: `./gradlew :payment-service:test --tests '*CancelOutboxRedriveRepository*' --tests '*CancelOutboxRedriveServiceTest'`

Expected: PASS, proving application transaction semantics still satisfy MySQL integration behavior.

- [ ] **Step 6: Commit the application slice**

```bash
git add payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveUseCase.java payment-service/src/main/java/com/example/payment/application/usecase/CancelOutboxRedriveQuery.java payment-service/src/main/java/com/example/payment/application/service/CancelOutboxRedriveService.java payment-service/src/main/java/com/example/payment/application/exception/CancelOutboxRedriveNotFoundException.java payment-service/src/main/java/com/example/payment/application/exception/InvalidRedriveReasonException.java payment-service/src/main/java/com/example/payment/common/exception/ErrorCode.java payment-service/src/test/java/com/example/payment/application/service/CancelOutboxRedriveServiceTest.java
git commit -m "feat(payment): request and query redrive jobs"
```

---

### Task 4: Internal HTTP Contracts and Asynchronous Boundary

**Files:**

- Create: `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveRequest.java`
- Create: `payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveResponse.java`
- Modify: `payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java`
- Modify: `payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java`

**Interfaces:**

- Consumes: Task 3 `CancelOutboxRedriveUseCase.request(...)` and `CancelOutboxRedriveQuery.get(...)`.
- Produces: `POST /internal/cancel-outbox/{outboxId}/redrives` returning HTTP 202.
- Produces: `GET /internal/cancel-outbox/redrives/{redriveId}` returning HTTP 200.

- [ ] **Step 1: Write failing controller contract tests**

Extend the existing standalone MockMvc setup with a real `CancelOutboxRedriveService` backed by a mocked repository and fixed clock. Keep the inspection use case mocked. Test:

- successful POST returns 202 and `REQUESTED` audit fields;
- successful GET returns all fields, including explicit null optional fields;
- missing role is 401 before use case invocation;
- missing operator ID and non-admin role are 403 before invocation;
- null, blank, and 501-code-point reasons return 400 and create no work;
- use case exceptions map to the stable 404/409 codes;
- the POST test has no inspection or replay dependency and completes below 500ms.

Use this response shape assertion:

```java
mockMvc.perform(post("/internal/cancel-outbox/41/redrives")
        .header("X-User-Role", "ADMIN")
        .header("X-User-Id", "operator-1")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"  장애 복구  \"}"))
    .andExpect(status().isAccepted())
    .andExpect(jsonPath("$.redriveId").value(7))
    .andExpect(jsonPath("$.sourceOutboxId").value(41))
    .andExpect(jsonPath("$.status").value("REQUESTED"))
    .andExpect(jsonPath("$.requestedBy").value("operator-1"))
    .andExpect(jsonPath("$.reason").value("  장애 복구  "))
    .andExpect(jsonPath("$.failureStage").value(IsNull.nullValue()));
```

- [ ] **Step 2: Run controller tests and verify RED**

Run: `./gradlew :payment-service:test --tests '*InternalCancelOutboxControllerTest'`

Expected: compilation failure or 404 because the new endpoints and DTOs do not exist.

- [ ] **Step 3: Implement DTOs and endpoints**

Keep the request DTO as a one-field record and delegate reason policy to the real use case. The standalone controller test's real service exercises the same validation used in production, so invalid inputs must leave its mocked repository untouched.

Add constructor dependencies for both new use cases and endpoints:

```java
@PostMapping("/internal/cancel-outbox/{outboxId}/redrives")
public ResponseEntity<CancelOutboxRedriveResponse> requestRedrive(
    @PathVariable long outboxId,
    @RequestHeader(value = "X-User-Role", required = false) String role,
    @RequestHeader(value = "X-User-Id", required = false) String operatorId,
    @RequestBody CancelOutboxRedriveRequest request
) {
    operatorAccess.requireAdmin(role, operatorId);
    return ResponseEntity.accepted().body(CancelOutboxRedriveResponse.from(
        redriveUseCase.request(outboxId, operatorId, request.reason())));
}

@GetMapping("/internal/cancel-outbox/redrives/{redriveId}")
public CancelOutboxRedriveResponse getRedrive(
    @PathVariable long redriveId,
    @RequestHeader(value = "X-User-Role", required = false) String role,
    @RequestHeader(value = "X-User-Id", required = false) String operatorId
) {
    operatorAccess.requireAdmin(role, operatorId);
    return CancelOutboxRedriveResponse.from(redriveQuery.get(redriveId));
}
```

Configure `CancelOutboxRedriveResponse` with `@JsonInclude(JsonInclude.Include.ALWAYS)` so nullable failure, timing, result, error, and snapshot fields remain explicit in the GET contract. Do not include source payload or payment key.

- [ ] **Step 4: Run controller tests and verify GREEN**

Run: `./gradlew :payment-service:test --tests '*InternalCancelOutboxControllerTest'`

Expected: PASS for existing inspection tests and all new POST/GET contracts.

- [ ] **Step 5: Add a real-context POST boundary integration assertion**

Add the assertion to `CancelOutboxRedriveRepositoryIT`: invoke the real service with the real repository and fixed clock, measure it with `System.nanoTime()`, and assert elapsed time is below 500ms, stored status is `REQUESTED`, and source status is `DEAD`. In `CancelOutboxRedriveServiceTest`, add `requestBoundaryDependsOnlyOnRepositoryAndClock`; reflect on the sole public constructor and assert its parameter types are exactly `CancelOutboxRedriveRepository` and `Clock`. This structurally proves POST cannot call `CancelOutboxInspectionUseCase`, `OrderCancelStatusPort`, `StockRestoreStatusPort`, or the issue #107 replay port.

- [ ] **Step 6: Run the payment-service regression suite**

Run: `./gradlew :payment-service:cleanTest :payment-service:test`

Expected: all payment-service unit and Testcontainers integration tests PASS.

- [ ] **Step 7: Verify formatting and changed-file scope**

Run: `git diff --check`

Run: `git status -sb`

Expected: no whitespace errors; only issue #106 implementation, tests, the approved design, and this plan are tracked changes. Pre-existing untracked user files remain untouched.

- [ ] **Step 8: Commit the HTTP slice**

```bash
git add payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveRequest.java payment-service/src/main/java/com/example/payment/presentation/dto/CancelOutboxRedriveResponse.java payment-service/src/main/java/com/example/payment/presentation/controller/InternalCancelOutboxController.java payment-service/src/test/java/com/example/payment/presentation/controller/InternalCancelOutboxControllerTest.java
git commit -m "feat(payment): expose cancel outbox redrive API"
```

---

## Final Verification

- [ ] Run `./gradlew :payment-service:cleanTest :payment-service:test` and confirm all tests pass.
- [ ] Run `./gradlew :payment-service:jacocoTestCoverageVerification` and confirm the payment-service coverage gate passes.
- [ ] Run `git diff --check` and confirm no whitespace errors.
- [ ] Run `git status -sb` and verify unrelated untracked user files were not staged or modified.
- [ ] Compare every GitHub issue #106 Acceptance Criterion with a named automated test and record the mapping in the PR body.
