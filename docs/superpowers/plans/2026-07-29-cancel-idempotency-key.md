# Cancel Idempotency-Key 멱등성 재구성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결제 취소 멱등성을 서버 content-hash 단독에서 클라이언트 `Idempotency-Key`(optional) + content-hash fallback으로 재구성한다. Toss/Stripe 정합, 재사용 409, money guard 무변경.

**Architecture:** `dedup_key = idempotency_key ? "ik:"+key : "ch:"+request_hash` (MySQL generated STORED column). UK `(payment_id, request_hash)` → `(payment_id, dedup_key)`. 조회는 dedup_key 기반(request_hash가 더는 unique 아님). 이중취소 가드는 TX3 아이템 상태머신(무변경).

**Tech Stack:** Java 21 · Spring Boot 3.x · JPA + native SQL · MySQL 8(generated column) · Flyway · JUnit5 + Mockito + Testcontainers · MockMvc.

**설계 스펙:** `docs/superpowers/specs/2026-07-29-cancel-idempotency-key-design.md`

## Global Constraints

- Flyway-only DDL — 새 버전 V15만 추가, 적용 파일 수정 금지.
- `request_hash`는 NOT NULL 유지(항상 계산, fingerprint). `idempotency_key` nullable(≤255).
- money guard(TX3 `findAllByPaymentIdForUpdate` + `cancelDomainService.apply`) 무변경.
- 도메인 `CancelRequest`에 Spring/JPA 어노테이션 금지.
- back-compat: 키 미전송 = `"ch:"+content-hash` = 기존 동작. 기존 테스트(특히 RESIL-02 `CancelRaceIdempotencyIT`) 무키 경로 green 유지.
- 재사용 일관성: 같은 키 + 다른 request_hash → 409 `IDEMPOTENCY_KEY_CONFLICT`.
- 네임스페이스 접두 `ik:`/`ch:` — 클라 키와 content-hash 충돌 차단.
- 테스트 없이 완료 금지. 각 태스크 TDD(RED→GREEN) + 원자 커밋. Docker up(Testcontainers).

---

### Task 1: V15 마이그레이션 + dedup_key generated column + 엔티티/도메인 필드

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V15__add_cancel_idempotency_key.sql`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaEntity.java` (add idempotencyKey; dedup_key는 DB generated라 읽기전용/미매핑 또는 insertable=false updatable=false)
- Modify: `payment-service/src/main/java/com/example/payment/domain/entity/CancelRequest.java` (idempotencyKey 필드 + create/reconstruct 확장)
- Modify: `payment-service/src/test/.../CancelRequestFixture.java` 및 reconstruct 호출부(컴파일 유지)
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImplTest.java` (또는 실제 IT 파일 — 존재 파일명 확인 후 케이스 추가)

**Interfaces:**
- Produces: `cancel_request.idempotency_key VARCHAR(255) NULL`, `dedup_key VARCHAR(300)` STORED generated, UK `uk_cancel_request_dedup (payment_id, dedup_key)` (기존 `uk_cancel_request_hash` 대체). 도메인 `CancelRequest.getIdempotencyKey()` + `create(..., idempotencyKey)`.

- [ ] **Step 1: 마이그레이션**
```sql
-- V15__add_cancel_idempotency_key.sql
-- 클라 Idempotency-Key(optional) + content-hash fallback. dedup_key(generated) UK로 교체.
ALTER TABLE cancel_request
    ADD COLUMN idempotency_key VARCHAR(255) NULL AFTER request_hash,
    ADD COLUMN dedup_key VARCHAR(300)
        AS (CONCAT(CASE WHEN idempotency_key IS NOT NULL THEN 'ik:' ELSE 'ch:' END,
                   COALESCE(idempotency_key, request_hash))) STORED,
    DROP KEY uk_cancel_request_hash,
    ADD UNIQUE KEY uk_cancel_request_dedup (payment_id, dedup_key);
```

- [ ] **Step 2: 엔티티/도메인** — JpaEntity에 `@Column(name="idempotency_key") private String idempotencyKey;` (dedup_key는 매핑 안 하거나 `@Column(insertable=false, updatable=false)` 읽기전용). 도메인 `CancelRequest.create(...)`/`reconstruct(...)`에 `idempotencyKey` 파라미터 추가(맨 뒤), getter. `create()`의 기존 호출부와 fixture는 `null` 또는 값 전달로 컴파일 유지.

- [ ] **Step 3: RED 테스트 (Testcontainers)** — 무키 INSERT → 재조회 시 `idempotency_key=null`; 키 INSERT("k1") → `idempotency_key="k1"`. (dedup_key 검증은 Task 2에서 조회로.) 실행 `./gradlew :payment-service:test --tests "*CancelRequest*"` → 필드 매핑 전 실패.

- [ ] **Step 4: GREEN** — 통과 확인.

- [ ] **Step 5: Commit** — `feat(idem): V15 idempotency_key + dedup_key generated column + 엔티티/도메인`

---

### Task 2: 리포지토리 findByPaymentIdAndDedupKey

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelRequestRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestJpaRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelRequestRepositoryImpl.java`
- Test: `CancelRequestRepositoryImplTest`(실제 IT 파일)

**Interfaces:**
- Produces: `Optional<CancelRequest> findByPaymentIdAndDedupKey(long paymentId, String dedupKey)` (인터페이스+Impl+JPA). 기존 `findByPaymentIdAndRequestHash`는 Task 5에서 마지막 사용처 제거 후 정리 판단(당장 유지 가능).

- [ ] **Step 1: 인터페이스 + JPA + Impl 추가** — JPA: `Optional<CancelRequestJpaEntity> findByPaymentIdAndDedupKey(Long paymentId, String dedupKey);` (Spring Data 파생 쿼리 — 엔티티에 `dedupKey` 읽기전용 매핑 필요, 또는 `@Query`로 dedup_key WHERE). 매핑이 번거로우면 `@Query("... WHERE payment_id=:p AND dedup_key=:d")` nativeQuery.

- [ ] **Step 2: RED 테스트 (Testcontainers)** — (a) 무키 행 INSERT 후 `findByPaymentIdAndDedupKey(pid, "ch:"+hash)` 로 조회됨. (b) 같은 items(같은 request_hash)로 키 다른 2행 INSERT → 각각 `"ik:k1"`, `"ik:k2"`로 개별 조회, 서로 간섭 없음(= request_hash 중복 허용, dedup_key 유일 확인). 실행 후 실패.

- [ ] **Step 3: GREEN** — 구현.

- [ ] **Step 4: GREEN 확인** — `./gradlew :payment-service:test --tests "*CancelRequestRepositoryImplTest"`.

- [ ] **Step 5: Commit** — `feat(idem): findByPaymentIdAndDedupKey 리포지토리`

---

### Task 3: Command + Controller 헤더 배선

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentCommand.java`
- Modify: `payment-service/src/main/java/com/example/payment/presentation/controller/CancelController.java`
- Test: `payment-service/src/test/.../presentation/controller/CancelControllerTest.java`(존재 시 케이스 추가, 없으면 MockMvc 신규)

**Interfaces:**
- Produces: `CancelPaymentCommand(paymentKey, cancelReason, cancelPaymentItemIds, idempotencyKey)` (nullable 마지막 필드). Controller가 `@RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey` 읽어 command에 전달.

- [ ] **Step 1: RED 테스트 (MockMvc)** — POST cancel with `Idempotency-Key: k1` 헤더 → usecase mock이 받은 command의 `idempotencyKey()=="k1"`; 헤더 없으면 `null`. `ArgumentCaptor<CancelPaymentCommand>`. 실행 후 실패(필드 없음).

- [ ] **Step 2: GREEN** — command에 `String idempotencyKey` 추가; CancelController에 `@RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey` 파라미터 + `new CancelPaymentCommand(paymentKey, request.cancelReason(), itemIds, idempotencyKey)`. 기존 command 생성 호출부(다른 곳/테스트) 컴파일 유지.

- [ ] **Step 3: GREEN 확인** — `./gradlew :payment-service:test --tests "*CancelControllerTest"`.

- [ ] **Step 4: Commit** — `feat(idem): CancelPaymentCommand.idempotencyKey + Idempotency-Key 헤더 배선`

---

### Task 4: CancelPaymentService dedup 로직 + 409 재사용 검증

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/CancelPaymentService.java`
- Create: `payment-service/src/main/java/com/example/payment/domain/exception/IdempotencyKeyConflictException.java` (또는 application 예외 계층 규약 위치 — conventions/architecture.md 확인)
- Modify: `payment-service/src/main/java/com/example/payment/presentation/GlobalExceptionHandler.java` (409 매핑)
- Test: `payment-service/src/test/.../application/service/CancelPaymentServiceTest.java`

**Interfaces:**
- Consumes: `findByPaymentIdAndDedupKey`(T2), `CancelRequest.create(...idempotencyKey)`(T1), `command.idempotencyKey()`(T3).
- Produces: dedup 로직 — effectiveDedup 계산, dedup_key 조회, 409 `IdempotencyKeyConflictException`(같은 키+다른 request_hash), CancelRequest에 idempotencyKey 실어 저장.

- [ ] **Step 1: RED 테스트** — 4 케이스(Mockito): (a) 키 있고 기존 dedup 행의 request_hash가 이번과 **같음** → handleExistingRequest 반환(멱등, 신규 INSERT 없음). (b) 키 있고 기존 행 request_hash **다름** → `IdempotencyKeyConflictException`. (c) 키 없음 → `"ch:"+hash`로 조회(fallback), 기존 동작. (d) saveTx1 `DataIntegrityViolationException`(race-loser) → `findByPaymentIdAndDedupKey`로 재조회 후 handleExistingRequest. 실행 후 실패.

- [ ] **Step 2: GREEN — cancel() 로직 수정**
```java
String requestHash = RequestHashGenerator.generate(command.paymentKey(), itemIds);
String dedupKey = command.idempotencyKey() != null
    ? "ik:" + command.idempotencyKey() : "ch:" + requestHash;
var existing = cancelRequestRepository.findByPaymentIdAndDedupKey(payment.getId(), dedupKey);
if (existing.isPresent()) {
    if (command.idempotencyKey() != null
            && !existing.get().getRequestHash().equals(requestHash)) {
        throw new IdempotencyKeyConflictException(command.idempotencyKey());
    }
    return handleExistingRequest(existing.get(), command, payment, items);
}
return executeCancel(payment, items, requestHash, command); // executeCancel: CancelRequest.create(..., command.idempotencyKey()); race-loser catch도 dedupKey 재조회
```
`executeCancel`의 `saveTx1` UK 위반 catch(현 `:110`)를 `findByPaymentIdAndDedupKey(payment.getId(), dedupKey)`로 교체. `CancelRequest.create(...)`에 `command.idempotencyKey()` 전달. `IdempotencyKeyConflictException` → GlobalExceptionHandler에서 409 + `IDEMPOTENCY_KEY_CONFLICT`.

- [ ] **Step 3: GREEN 확인 + 회귀** — `./gradlew :payment-service:test --tests "*CancelPaymentServiceTest"` + 전체 모듈(기존 CancelRaceIdempotencyIT 무키 경로 green 유지).

- [ ] **Step 4: Commit** — `feat(idem): dedup_key 기반 멱등 + 409 재사용 검증`

---

### Task 5: ProcessingRecovery 재조회 dedup_key 전환

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/service/ProcessingRecoveryService.java` (`:119` 재조회)
- Test: `ProcessingRecoveryServiceTest`

**Interfaces:**
- Consumes: `findByPaymentIdAndDedupKey`(T2).

- [ ] **Step 1: RED/조정** — `ProcessingRecoveryService:119`의 `findByPaymentIdAndRequestHash(paymentId, requestHash)`는 request_hash가 더는 unique가 아니라 잘못된 행을 집을 위험. 해당 CancelRequest의 dedup_key(또는 id)로 재조회하도록 변경. 기존 `ProcessingRecoveryServiceTest`가 이 재조회를 mock하는 지점을 dedup_key 기반으로 조정(assert 의미 불변). RED: 시그니처/스텁 불일치.

- [ ] **Step 2: GREEN** — `retryPgCancel`의 재조회를 `cancelRequestRepository.findByPaymentIdAndDedupKey(cancelRequest.getPaymentId(), cancelRequest.getDedupKey())` 또는 `findById(cancelRequest.getId())`로. (도메인에 dedupKey 노출이 없으면 `findById`가 단순 — 권장.) 재조회 목적(원자 UPDATE 후 stale 갱신)은 그대로.

- [ ] **Step 3: GREEN 확인** — `./gradlew :payment-service:test --tests "*ProcessingRecovery*"`.

- [ ] **Step 4: Commit** — `fix(idem): ProcessingRecovery 재조회를 유일키(dedup_key/id) 기반으로`

---

### Task 6: 문서 정정 (error-catalog / api-spec / domain-rules / db-schema / CLAUDE.md)

**Files:**
- Modify: `docs/error-catalog.md` (신규 `IDEMPOTENCY_KEY_CONFLICT` 409)
- Modify: `docs/api-spec.md` (취소 엔드포인트 `Idempotency-Key` optional 헤더 + 409 응답)
- Modify: `docs/domain-rules.md` (§멱등성: content-hash 단독 → 클라 키+fallback, dedup_key UK)
- Modify: `docs/db-schema.md` + `CLAUDE.md` (핵심 불변식 request_hash 서술 갱신)

- [ ] **Step 1: 문서 갱신** — 위 5개 문서를 구현된 동작에 정확히 맞춤(발명 금지). CLAUDE.md 멱등성 불변식: "request_hash = SHA-256(...), 서버 생성 (Idempotency-Key 헤더 없음)" → "클라 Idempotency-Key(있으면) / content-hash(fallback), dedup_key=`ik:`/`ch:` 접두, UK (payment_id, dedup_key). 재사용 불일치 409."

- [ ] **Step 2: (테스트 불요 — 문서)** 확인만: `./gradlew :payment-service:test` 전체 green(코드 무변경).

- [ ] **Step 3: Commit** — `docs(idem): error-catalog/api-spec/domain-rules/db-schema/CLAUDE.md 멱등성 갱신`

---

## 검증 (전체 완료 후)
- `./gradlew :payment-service:test` 전체 green (신규 idem 테스트 + 기존 회귀, RESIL-02 무키 경로 포함).
- 409가 `IDEMPOTENCY_KEY_CONFLICT`로 매핑되는지 exception→handler 테스트.

## Out of Scope
- TTL/키 만료. request_hash 입력 canonicalization 견고성. order-service. 클라이언트측 키 도입 배포 조율.
