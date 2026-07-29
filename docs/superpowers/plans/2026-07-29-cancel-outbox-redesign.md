# Cancel Outbox 재기획 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `payment.cancelled` 발행을 Transactional Outbox 정식 기본 + 이벤트 드리븐 wake로 전환하고, poison 재시도 관리와 보존 purge를 추가해 TX3 dual-write를 제거한다.

**Architecture:** 이벤트는 TX3와 같은 커밋으로 `cancel_event_outbox`에 원자적 INSERT(유일 권위 write). relay(RLock 단일 인스턴스)가 `@Scheduled` poll(정합 backstop) + Redisson `RTopic` wake(afterCommit, 비권위 저지연 트리거)로 PENDING을 발행. 실패는 retry_count 누적 후 MAX 초과 시 DEAD 격리+알림. 별도 purge 잡이 오래된 PUBLISHED를 삭제.

**Tech Stack:** Java 21 · Spring Boot 3.x · Spring Data JPA + native SQL · MySQL 8 · Flyway · Kafka · Redisson(RLock/RTopic) · JUnit5 + Mockito + Testcontainers.

**설계 스펙:** `docs/superpowers/specs/2026-07-29-cancel-outbox-redesign-design.md`

## Global Constraints

- Flyway-only DDL — 적용된 마이그레이션 파일 수정 금지, 새 버전(V14)만 추가.
- 아웃박스 write는 반드시 메인 풀(JPA, TX3 커밋과 원자적) — poller 경로만 전용 풀(`cancelOutboxJdbcTemplate`).
- `INLINE`/`INLINE_ASYNC` 구현 제거 금지 (벤치·학습 자산).
- `poll` backstop 제거 금지 — wake는 비권위, poll이 at-least-once 정합 보장.
- 테스트 없이 구현 완료 금지. 각 태스크 TDD(RED→GREEN) + 원자 커밋.
- MAX 재시도 기본 `cancel.outbox.max-retries=10`, 보존 `cancel.outbox.retention-days=7`.
- 신규 락 키 `lock:scheduler:cancel-outbox-purge`.

---

### Task 1: V14 마이그레이션 + 엔티티 필드 (retry_count/last_error)

**Files:**
- Create: `payment-service/src/main/resources/db/migration/V14__add_outbox_retry_columns.sql`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxJpaEntity.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImplTest.java` (기존 IT에 케이스 추가)

**Interfaces:**
- Produces: `cancel_event_outbox` 컬럼 `retry_count INT NOT NULL DEFAULT 0`, `last_error VARCHAR(500) NULL`; status 값 규약에 `DEAD` 추가(스키마 변경 없음).

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- V14__add_outbox_retry_columns.sql
-- cancel_event_outbox: poison 재시도 관리용 retry_count + last_error 추가.
-- status 값 규약 확장: PENDING | PUBLISHED | DEAD (컬럼 타입 변경 없음).
ALTER TABLE cancel_event_outbox
    ADD COLUMN retry_count INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error  VARCHAR(500) NULL;
```

- [ ] **Step 2: 엔티티 필드 추가** — `CancelEventOutboxJpaEntity`에 `private int retryCount;` `private String lastError;` + `@Column(name="retry_count")`/`@Column(name="last_error")` 매핑 (기존 필드 스타일 그대로). 읽기 전용 poller는 JDBC를 쓰므로 엔티티는 write(insert)와 왕복 테스트에만 사용.

- [ ] **Step 3: RED 테스트** — Testcontainers 실 MySQL로 `insertPendingIdempotent` 후 조회 시 `retry_count=0`, `last_error=null` 확인하는 케이스 추가. 실행 `./gradlew :payment-service:test --tests "*CancelEventOutboxRepositoryImplTest"` → 컬럼 매핑 전 실패.

- [ ] **Step 4: GREEN** — 엔티티/마이그레이션으로 통과 확인 (동일 커맨드).

- [ ] **Step 5: Commit** — `feat(outbox): V14 retry_count/last_error 컬럼 + 엔티티 매핑`

---

### Task 2: 리포지토리 — poison/purge 쿼리 + findPendingBatch에 retry_count 노출

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/application/interfaces/CancelEventOutboxRepository.java`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImpl.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/persistence/CancelEventOutboxRepositoryImplTest.java`

**Interfaces:**
- Produces:
  - `record PendingOutbox(long id, long cancelRequestId, String payload, int retryCount)` (retryCount 추가)
  - `void bumpRetry(long id, String lastError)` — retry_count+1, last_error 갱신 (PENDING 유지)
  - `void markDead(long id, String lastError)` — status='DEAD', last_error 갱신
  - `int purgePublished(int retentionDays)` — 삭제된 행 수 반환
  - 기존 `markPublished(List<Long>)` 유지

- [ ] **Step 1: 인터페이스 확장** — 위 메서드 시그니처 추가, `PendingOutbox`에 `retryCount` 필드 추가.

- [ ] **Step 2: RED 테스트 (Testcontainers)** — 4 케이스: (a) `findPendingBatch`가 retry_count 포함 반환, (b) `bumpRetry` → retry_count 1 증가 + last_error 기록 + 여전히 PENDING, (c) `markDead` → status='DEAD' 되어 `findPendingBatch`에서 제외, (d) `purgePublished(7)` → published_at 8일 전 PUBLISHED 삭제, PENDING/최근 PUBLISHED 보존. 실행 후 실패 확인.

- [ ] **Step 3: GREEN — Impl 쿼리 구현** (전용 풀 `outboxJdbc` 사용)

```java
// findPendingBatch: SELECT에 retry_count 추가
"SELECT id, cancel_request_id, payload, retry_count FROM cancel_event_outbox "
  + "WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit"
// rowMapper: new PendingOutbox(id, cancel_request_id, payload, rs.getInt("retry_count"))

// bumpRetry
"UPDATE cancel_event_outbox SET retry_count = retry_count + 1, last_error = :err "
  + "WHERE id = :id"
// markDead
"UPDATE cancel_event_outbox SET status = 'DEAD', last_error = :err WHERE id = :id"
// purgePublished → update() 반환값(int) 사용
"DELETE FROM cancel_event_outbox WHERE status = 'PUBLISHED' "
  + "AND published_at < (CURRENT_TIMESTAMP(3) - INTERVAL :days DAY)"
```
`last_error`는 500자 초과 방지로 `error != null ? error.substring(0, Math.min(500, error.length())) : null` 로 절단.

- [ ] **Step 4: GREEN 확인** — 동일 테스트 커맨드 green.

- [ ] **Step 5: Commit** — `feat(outbox): bumpRetry/markDead/purgePublished 쿼리 + findPendingBatch retry_count`

---

### Task 3: relay poison 분기 (실패 → retry/DEAD+alert)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisherTest.java` (신규 — 존재 시 케이스 추가)

**Interfaces:**
- Consumes: `CancelEventOutboxRepository.bumpRetry/markDead`, `OperationAlertPort.alert(String)`.
- Produces: relay가 send 실패 시 `retryCount+1 >= maxRetries`면 `markDead + alert`, 아니면 `bumpRetry`.

- [ ] **Step 1: 생성자에 `OperationAlertPort` 주입 + `@Value("${cancel.outbox.max-retries:10}") int maxRetries` 추가.**

- [ ] **Step 2: RED 테스트** — `KafkaTemplate` mock으로 send future가 예외 완료되도록. 2 케이스: (a) retry_count=0인 PENDING 1건 send 실패 → `bumpRetry(id, err)` 호출, `markDead`/`alert` 미호출. (b) retry_count=9(=max-1)인 건 send 실패 → `markDead(id, err)` + `alert(포함: outboxId)` 호출. 성공분은 기존대로 `markPublished`. 실행 후 실패.

- [ ] **Step 3: GREEN — ack 대기 루프 수정**

```java
// 2) ack 일괄 대기 — 성공/실패 분기
List<Long> published = new ArrayList<>();
for (var s : inFlight) {
    try {
        s.future().get(30, TimeUnit.SECONDS);
        published.add(s.id());
    } catch (Exception e) {
        String err = e.getMessage();
        if (s.retryCount() + 1 >= maxRetries) {
            outboxRepository.markDead(s.id(), err);
            operationAlertPort.alert(
                "[outbox] 발행 영구 실패(DEAD) outboxId=" + s.id() + " err=" + err);
            log.error("[outbox] DEAD 전이 outboxId={}", s.id(), e);
        } else {
            outboxRepository.bumpRetry(s.id(), err);
            log.warn("[outbox] 발행 실패 재시도 예정 outboxId={} retry={}", s.id(), s.retryCount() + 1);
        }
    }
}
outboxRepository.markPublished(published);
```
`InFlight` record에 `int retryCount` 추가하고, send 발사 시 `new InFlight(o.id(), o.retryCount(), kafkaTemplate.send(...))`로 채운다.

- [ ] **Step 4: GREEN 확인** — `./gradlew :payment-service:test --tests "*CancelEventOutboxPublisherTest"`.

- [ ] **Step 5: Commit** — `feat(outbox): relay poison 분기 — MAX 초과 DEAD+alert, 그 전 retry`

---

### Task 4: purge 스케줄러 (별도 @Scheduled + 자체 RLock)

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java` (동일 클래스에 별도 메서드) 또는 Create 신규 `CancelOutboxPurgeScheduler.java`
- Modify: `payment-service/src/main/resources/application.yml`
- Test: `CancelEventOutboxPublisherTest` 또는 신규 purge 테스트

**Interfaces:**
- Consumes: `CancelEventOutboxRepository.purgePublished(int)`, `RedissonClient`.
- Produces: 하루 1회 오래된 PUBLISHED 삭제.

- [ ] **Step 1: application.yml 추가** — `cancel.outbox` 하위에 `max-retries: ${CANCEL_OUTBOX_MAX_RETRIES:10}`, `retention-days: ${CANCEL_OUTBOX_RETENTION_DAYS:7}`; `scheduler.lock`에 `cancel-outbox-purge: lock:scheduler:cancel-outbox-purge`.

- [ ] **Step 2: RED 테스트** — purge 메서드가 RLock 획득 후 `purgePublished(retentionDays)` 호출하는지 (repo mock verify). 실행 후 실패.

- [ ] **Step 3: GREEN — @Scheduled 메서드**

```java
@Scheduled(fixedDelayString = "${cancel.outbox.purge-ms:86400000}")
public void purge() {
    RLock lock = redissonClient.getLock(purgeLockKey);
    try {
        if (!lock.tryLock(0, 300, TimeUnit.SECONDS)) return;
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    try {
        int deleted = outboxRepository.purgePublished(retentionDays);
        if (deleted > 0) log.info("[outbox-purge] 삭제 {}건 (보존 {}일)", deleted, retentionDays);
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```
`@Value("${cancel.outbox.retention-days:7}") int retentionDays`, `@Value("${scheduler.lock.cancel-outbox-purge}") String purgeLockKey` 주입.

- [ ] **Step 4: GREEN 확인.**

- [ ] **Step 5: Commit** — `feat(outbox): PUBLISHED 보존 purge 스케줄러(자체 락)`

---

### Task 5: 이벤트 드리븐 wake — publisher afterCommit RTopic

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisher.java`
- Test: `payment-service/src/test/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisherTest.java` (신규)

**Interfaces:**
- Consumes: `RedissonClient` (RTopic).
- Produces: `insertPending` 후 **커밋 성공 시** `RTopic("cancel-outbox-wake").publish(cancelRequestId)`. 트랜잭션 없으면 즉시 publish(폴백).

- [ ] **Step 1: RED 테스트** — `TransactionSynchronizationManager` 활성 트랜잭션 컨텍스트에서 `publish()` 호출 → `insertPending` 즉시 호출 + wake는 afterCommit까지 미발사; 커밋 시뮬레이션 후 `RTopic.publish` 발사. (Redisson `RTopic` mock verify.) 트랜잭션 없을 때 즉시 publish. 실행 후 실패.

- [ ] **Step 2: GREEN — afterCommit 등록**

```java
private static final String WAKE_TOPIC = "cancel-outbox-wake";

@Override
public void publish(long cancelRequestId, String payload) {
    outboxRepository.insertPending(cancelRequestId, payload); // TX3 커밋과 원자적
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { wake(cancelRequestId); }
            });
    } else {
        wake(cancelRequestId); // 트랜잭션 밖(복구 등) — 즉시
    }
}

private void wake(long cancelRequestId) {
    try {
        redissonClient.getTopic(WAKE_TOPIC).publish(cancelRequestId);
    } catch (Exception e) {
        // wake는 비권위 — 실패해도 poll backstop이 커버. 로그만.
        log.debug("[outbox] wake 발사 실패(무해, poll이 커버) cancelRequestId={}", cancelRequestId);
    }
}
```
생성자에 `RedissonClient` 주입.

- [ ] **Step 3: GREEN 확인** — `./gradlew :payment-service:test --tests "*OutboxCancelEventPublisherTest"`.

- [ ] **Step 4: Commit** — `feat(outbox): afterCommit RTopic wake (비권위 저지연 트리거)`

---

### Task 6: 이벤트 드리븐 wake — relay 구독 + coalesce

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/scheduler/CancelEventOutboxPublisher.java`
- Test: `CancelEventOutboxPublisherTest`

**Interfaces:**
- Consumes: `RedissonClient.getTopic("cancel-outbox-wake")`.
- Produces: wake 수신 시 coalesced 폴 1회 트리거. `@Scheduled` backstop 유지.

- [ ] **Step 1: RED 테스트** — wake 리스너 등록(@PostConstruct) 확인 + 리스너가 폴 로직을 호출하는지; 동시 다발 wake가 하나의 폴로 합류(single-permit)하는지 (폴 진행 중 추가 wake는 skip). 실행 후 실패.

- [ ] **Step 2: GREEN — 구독 + coalesce**

```java
private final AtomicBoolean pollScheduled = new AtomicBoolean(false);

@PostConstruct
void subscribeWake() {
    redissonClient.getTopic("cancel-outbox-wake")
        .addListener(Long.class, (channel, msg) -> triggerPoll());
}

private void triggerPoll() {
    if (!pollScheduled.compareAndSet(false, true)) return; // coalesce: 이미 예약됨
    try { publish(); } finally { pollScheduled.set(false); }
}
```
기존 `@Scheduled publish()`는 그대로 backstop. `triggerPoll`은 wake 경로, 둘 다 RLock으로 단일 발행 보장(리더만 실제 발행). coalesce는 인스턴스 내 폴 폭주 방지.

- [ ] **Step 3: GREEN 확인.**

- [ ] **Step 4: Commit** — `feat(outbox): relay wake 구독 + coalesce (저지연 발행)`

---

### Task 7: OUTBOX 정식 기본 전환 + 문서 정정

**Files:**
- Modify: `payment-service/src/main/resources/application.yml`
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/OutboxCancelEventPublisher.java` (matchIfMissing)
- Modify: `payment-service/src/main/java/com/example/payment/infrastructure/messaging/InlineCancelEventPublisher.java` (matchIfMissing 제거)
- Modify: `CLAUDE.md`, `docs/kafka-design.md`

**Interfaces:**
- Produces: 설정 부재 시 OUTBOX가 활성 발행 모드.

- [ ] **Step 1: RED 테스트** — Spring 컨텍스트 슬라이스 테스트: `cancel.publish.mode` 미설정 시 활성 `CancelEventPublisher` 빈이 `OutboxCancelEventPublisher`인지. 실행 후 실패(현재 INLINE).

- [ ] **Step 2: GREEN**
  - `application.yml`: `publish.mode: ${CANCEL_PUBLISH_MODE:OUTBOX}`.
  - `OutboxCancelEventPublisher`의 `@ConditionalOnProperty(... havingValue="OUTBOX", matchIfMissing=true)`.
  - `InlineCancelEventPublisher`의 `matchIfMissing=true` 제거(`havingValue="INLINE"`만).

- [ ] **Step 3: 회귀 점검** — 기본 전환으로 깨지는 기존 테스트(INLINE 전제) 탐색·정정: `./gradlew :payment-service:test` 전체 실행. INLINE 특정 동작을 검증하던 테스트는 `@TestPropertySource("cancel.publish.mode=INLINE")`로 명시 고정.

- [ ] **Step 4: 문서 정정** — `CLAUDE.md`의 "payment.cancelled: payment-service가 TX3 인라인 발행" → "OUTBOX 정식(TX3 원자 outbox + 이벤트 wake relay), 발행 실패는 relay 재시도/DEAD"로. `docs/kafka-design.md` 동일 취지 갱신.

- [ ] **Step 5: 전체 스위트 green 확인** — `./gradlew :payment-service:test` (Testcontainers 포함).

- [ ] **Step 6: Commit** — `feat(outbox): OUTBOX 정식 기본 전환 + CLAUDE.md/kafka-design 정정`

---

## 검증 (전체 완료 후)
- `./gradlew :payment-service:test` 전체 green (신규 outbox 테스트 + 기존 회귀).
- 스펙 §7 액션: order-service 컨슈머가 (a) 중복 이벤트 멱등, (b) 순서무관 수렴하는지 코드 확인 — 미충족 시 별도 이슈 승격(이 계획 밖).

## Out of Scope (별도)
- request_hash/멱등성 재구성.
- 실 Toss url/Basic 인증.
- order-service 컨슈머 코드 변경.
