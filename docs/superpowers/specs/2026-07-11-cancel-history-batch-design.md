# 취소 이력 배치 — 커밋 감축 (150rps 천장 완화) 설계

- 날짜: 2026-07-11
- 상태: 설계 확정
- 관련: 2026-07-11 AWS 실측(포화 진단), 메모리 `loadtest-aws-run`, `sysdesign/cancel-design.md`, CLAUDE.md 불변식

## 배경 / 문제

2026-07-11 AWS 실측으로 취소 처리량 천장(~147–185rps)의 인과를 규명함:

```
취소 1건 = 18 앱쿼리, 그중 COMMIT 6 → 2코어 DB(m7g.large)에서 커넥션 ~66ms 점유
→ 풀(HikariCP=10) / 66ms ≈ 150 rps 천장
```

커밋 6 = **코어 3 TX**(TX1 PENDING / TX2 PROCESSING / TX3 COMPLETED, 외부 호출로 분리) + **이력 3 TX**(`cancel_request_history` 상태전이 3회, 각각 `REQUIRES_NEW` 별도 커밋). 커밋은 fsync 바운드라 DB 시간의 주범이고, 이력 3 TX가 그 절반이다. risk 홉(server p95 21ms)·풀 크기(2코어 DB 공식 `(2×2)+1≈5` 기준 10은 이미 상한)는 병목이 아님이 실측으로 확인됨.

## 목표

**이력 3 커밋 → 1 커밋**으로 배치. 취소당 커밋 **6→4**. 커넥션 점유시간·fsync 부하를 낮춰 처리량 천장을 올린다. 이력 3건은 모두 보존(전이 로그 유지).

## 비목표 (YAGNI / 명시적 제외)

- **코어 TX 경계(TX1/2/3) 변경 금지** — 외부 호출(risk/PG)로 분리돼 있어 병합하면 "HTTP-in-TX 시한폭탄"(netem 실험). 3 커밋 유지.
- **비동기 이력(@Async) 아님** — 커밋을 옮길 뿐 총 fsync 부하를 안 줄여 처리량 이득 없음. 동기 배치.
- **터미널 상태만 기록 아님** — 전이 로그(PENDING/PROCESSING) 보존.
- **복구 서비스(Pending/ProcessingRecoveryService) 손대지 않음** — 각 1회 기록이라 배치 이득 없음. 단건 `record()` 그대로 사용.
- payment_item 중복 SELECT 제거(백로그 #2)·홉지연 히스토그램 버킷 노출은 별도 작업.

## 불변식 / 원칙 (유지 확인)

- **이력은 코어 TX 밖**(`REQUIRES_NEW`), best-effort(예외 삼킴, 비즈니스 무영향) — CLAUDE.md "이력을 TX1/2/3 안에 포함 금지" 유지.
- **복구는 `cancel_request.status` 기반** — `PendingRecoveryService.findPendingCreatedBefore`, `ProcessingRecoveryService.findProcessingUpdatedBefore`가 `cancel_request`를 조회(코드 확인). history 테이블 미의존 → 이력 배치 지연이 복구/정합성에 **무영향.**
- **self-invocation 없음** — `@Transactional(REQUIRES_NEW)`는 레포지토리 impl(`recordAll`)에 붙고 다른 빈(`CancelHistoryRecorder`)이 프록시로 호출. 기존 `record()` 호출 구조와 동일.

## 설계

### 핵심 아이디어

이력을 **전이 순간마다 메모리 버퍼에 적재(시각 캡처 포함)**하고, 취소 종료 시 **1 트랜잭션으로 일괄 INSERT**. `CancelRequestHistoryJpaEntity.of(...)`가 생성 시점에 `createdAt = Instant.now()`를 박으므로, add 시점에 시각을 캡처하면 INSERT를 나중에 배치해도 **전이 시각이 보존**된다(감사 세분성 유지).

### 구성요소

**1. `CancelHistoryEntry` (application 값객체, record)**
`(long cancelRequestId, CancelStatus status, String reason, Instant occurredAt)`. `occurredAt`은 add 시점에 캡처. 인프라(JPA 엔티티) 비의존 → 레이어 분리 유지.

**2. `CancelHistoryRecorder` (신규 `@Component`, ThreadLocal 버퍼)**
- `ThreadLocal<List<CancelHistoryEntry>>` 버퍼(스레드별 격리, 시그니처 오염 없이 helper에서 접근).
- `add(long cancelRequestId, CancelStatus status, String reason)` — `occurredAt = Instant.now()` 캡처해 버퍼에 적재. DB 접근 없음(in-memory).
- `flush()` — 버퍼 비어있지 않으면 `historyRepository.recordAll(List.copyOf(buffer))` 호출. try/catch로 예외 삼킴+`log.warn`(best-effort). `finally`에서 `ThreadLocal.remove()`(누수 방지).
- 자체는 `@Transactional` 아님(버퍼링+위임만).

**3. `CancelRequestHistoryRepository` (인터페이스 확장)**
- `void record(long, CancelStatus, String)` — **유지**(복구 서비스용, 단건 즉시).
- `void recordAll(List<CancelHistoryEntry> entries)` — **신규**. impl에 `@Transactional(propagation = REQUIRES_NEW)`, `jpaRepository.saveAll(...)` → **커밋 1개**.

**4. `CancelRequestHistoryJpaEntity` (오버로드 추가)**
- 기존 `of(id, status, reason)`(now 스탬프) 유지.
- `of(long id, String status, String reason, Instant createdAt)` 추가 — 배치가 캡처한 전이 시각을 전달. `recordAll` impl이 이 오버로드로 엔티티 생성.

**5. `CancelPaymentService` (호출부 교체 + 감쌈)**
- `recordHistory(...)` 내부를 `recorder.add(...)`로 교체(메서드 시그니처 유지).
- `cancel(...)` 본문을 `try { ... } finally { recorder.flush(); }`로 감쌈.
- **코어 TX(CancelTxWriter saveTx1/2/3)는 무변경.**

### 데이터 흐름

```
cancel(command) {
  try {
    saveTx1(PENDING)      → recorder.add(id, PENDING, null)      // 시각 캡처, DB 안 감
    risk.validateAndReserve(...)
    saveTx2(PROCESSING)   → recorder.add(id, PROCESSING, null)
    pg.cancel(...)
    saveTx3(COMPLETED)    → recorder.add(id, COMPLETED, null)
    return savedTx3
  } finally {
    recorder.flush()      // 버퍼 3건 → recordAll 1 TX = 커밋 1개
  }
}
```

실패 경로(risk 실패 throw / PG 실패 return / FAILED)도 버퍼에 해당 상태 적재 후 `finally`에서 함께 flush. 어떤 종료 경로든 flush 1회.

### 안전 / 트레이드오프

- 앱 크래시(플러시 전)로 **중간 이력(PENDING/PROCESSING) 유실 가능** — `cancel_request` 상태(코어 커밋)는 durable하므로 복구·정합성 무영향, 감사 세분성만 손실. 크래시는 드물고 이력은 이미 best-effort.
- ThreadLocal 누수: `flush()`가 항상 `finally`에서 `remove()`. `cancel()`의 `finally`가 flush를 보장.

## 테스트 전략

- **`CancelHistoryRecorder` 단위:** add 3회 후 flush → `recordAll` 1회 호출(인자 3건, occurredAt 캡처 순서), 버퍼 정리. add 0회 flush → repo 미호출. `recordAll` 예외 시 flush가 삼킴(전파 안 함). 서로 다른 스레드 버퍼 격리.
- **`recordAll` 통합(Testcontainers):** 3 `CancelHistoryEntry`(다른 occurredAt) → `cancel_request_history` 3행, 각 `created_at`이 전달한 occurredAt과 일치(전이순 보존).
- **취소 플로우 통합:** full 취소 1건 → history 3행 + `recordAll` 1회(단건 `record` 3회 아님). 기존 `CancelFlowIntegrationTest`의 이력-기록-시점 가정(인라인→종료)에 맞춰 조정.
- **복구 서비스 회귀:** 단건 `record()` 경로 무변경 — 기존 테스트 그린 유지.

## 검증 (실측)

키트 병합 후 다음 온디맨드 런은 **`OTEL_JAVAAGENT` 켜서** 실행 — Tempo 트레이스 스팬으로 (1) 취소당 커밋 4개 확인 (2) 커넥션 점유시간 단축 (3) stress 스윕 rps 재측정(150→?). 커밋 감소의 실효를 스팬·rps로 확증한다.

## 파일 변경 요약

| 파일 | 변경 |
|---|---|
| `application/.../CancelHistoryEntry.java` | 신규 값객체(record) |
| `application/.../CancelHistoryRecorder.java` | 신규 @Component(ThreadLocal 버퍼 + flush) |
| `application/interfaces/CancelRequestHistoryRepository.java` | `recordAll(List<CancelHistoryEntry>)` 추가 |
| `infrastructure/persistence/CancelRequestHistoryRepositoryImpl.java` | `recordAll` 구현(REQUIRES_NEW, saveAll) |
| `infrastructure/persistence/CancelRequestHistoryJpaEntity.java` | `of(...,Instant createdAt)` 오버로드 |
| `application/.../CancelPaymentService.java` | `recordHistory`→`recorder.add`, `cancel()` try/finally flush |

## 미해결 / 후속

- 실측으로 커밋 4 확인 후, 필요하면 JDBC batch insert(`hibernate.jdbc.batch_size`, `rewriteBatchedStatements`)로 3 INSERT를 1 왕복으로(커밋은 이미 1개라 부차적).
- 백로그: payment_item 중복 SELECT(2→1), 홉지연 히스토그램 버킷 노출.
