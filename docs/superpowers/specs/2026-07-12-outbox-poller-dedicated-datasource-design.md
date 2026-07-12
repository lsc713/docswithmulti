# 폴러 전용 DataSource — 설계 (Design)

**작성일:** 2026-07-12
**상태:** 승인됨 (brainstorming) → 계획(writing-plans) 대기
**배경 버그:** [`docs/load-test/outbox-poller-livelock.md`](../../load-test/outbox-poller-livelock.md)

## 1. 목적 (Purpose)

OUTBOX 발행 폴러의 **라이브락**을 없앤다. 폴러는 payment 앱과 **같은 Hikari 커넥션 풀(10)** 을 공유해, 취소 고부하 시 `markPublished`가 커넥션을 못 얻어(30s 타임아웃) 실패 → 오래된 head 배치가 영영 PENDING → 매 폴 같은 배치 재조회·재발송(2026-07-12 실측: sent 424,530 vs order 처리 1,000, PENDING 79,811/PUBLISHED 0). **폴러의 배수 경로(`findPendingBatch`·`markPublished`)를 소형 전용 커넥션 풀로 격리**해, 앱 풀 포화와 무관하게 폴러가 커넥션을 확보하도록 한다.

핵심 통찰: 라이브락은 **자원 양이 아니라 격리** 문제다. 폴러의 *스레드*(scheduling-1)는 멀쩡하나 공유 *커넥션 풀*에서 슬롯을 못 얻어 굶는다. 스케일업(풀 확대)은 포화점만 밀고, 스케일아웃은 분산락 때문에 폴러 용량이 안 늘어 무효. **커넥션 격리**가 최소 비용의 정확한 처방이다.

## 2. 범위 (Scope)

**In scope:**
- payment-service에 **소형 전용 HikariDataSource**(pool 2) + 그 위 **`cancelOutboxJdbcTemplate`** 추가 (OUTBOX 모드 전용).
- `CancelEventOutboxRepositoryImpl`의 경로 분리: `insertPending`→메인 JPA(TX3 유지), `findPendingBatch`·`markPublished`→전용 JdbcTemplate.
- AWS 재측정으로 라이브락 해소 검증 (별도 실행, 코드 아님).

**Out of scope (후속):**
- **CDC(Debezium)** — 전용 풀로 라이브락(스톨)을 없앤 뒤 남는 *단일 스레드 배수율* 상한이 CDC의 다음 논거. 별도 스펙.
- 폴러 **배수 병렬화**(멀티 스레드) — 단일 스레드 상한이 재측정에서 병목으로 확인되면 검토.
- INLINE/INLINE_ASYNC 경로 — 무변경.
- 커넥션 풀 확대·인스턴스 스케일 — §7 대안 검토에서 기각.

## 3. 아키텍처 (Architecture)

### 3.1 경로별 풀 분리 (핵심 결정)

```
insertPending    → 기존 JPA repo (메인 풀 10, TX3 안)   ← 원자성 유지 (outbox 불변식)
findPendingBatch → 전용 JdbcTemplate (전용 풀 2)         ← 폴러 배수 경로, 격리
markPublished    → 전용 JdbcTemplate (전용 풀 2)         ← 폴러 배수 경로, 격리
```

- **`insertPending`은 메인 풀 유지 필수.** 취소 TX3(`CancelTxWriter.saveTx3`, `REQUIRES_NEW`) 안에서 호출되어 outbox 행이 비즈니스 커밋과 **원자적**이어야 한다(outbox 불변식). 전용 풀로 옮기면 별도 트랜잭션이 되어 dual-write가 재발한다 → **금지**.
- **`findPendingBatch`·`markPublished`는 전용 풀로 격리.** 비즈니스 TX 밖의 배수 경로다. 메인 풀(insertPending의 TX3)과 다른 커넥션·트랜잭션이어도 정합성 무관 — 폴러는 이미 커밋된 outbox 행을 읽고 표시할 뿐이고, `cancel_request_id` UK가 멱등을 보장한다.

### 3.2 왜 전용 풀이 라이브락을 없애나

전용 풀 2는 폴러 단일 스레드만 사용 → 경합 ≈ 0 → 폴러가 **항상 커넥션 확보**. 실패 모드가 **"커넥션 못 얻음(스톨=라이브락)" → "커넥션 얻음, DB에서 처리(진전)"** 로 바뀐다. 폴러 쿼리는 인덱스 SELECT/UPDATE(마이크로~밀리초)라 DB가 취소 부하로 바빠도 빠르게 완료된다. DB 커넥션 예산: 메인 10 + 전용 2 = 12, MySQL 기본 151에 여유.

## 4. 컴포넌트 (Components)

### 4.1 `CancelOutboxDataSourceConfig` (신규, OUTBOX 전용)
- `@Configuration`, `@ConditionalOnProperty(name="cancel.publish.mode", havingValue="OUTBOX")` — INLINE(기본/프로덕션)에선 전용 풀 미생성(유휴 커넥션 0).
- 빈: `cancelOutboxDataSource`(HikariDataSource, `@ConfigurationProperties("cancel.outbox.datasource.hikari")`) + `cancelOutboxJdbcTemplate`(JdbcTemplate).
- 설정: 같은 payment_db(url/creds 동일), `maximum-pool-size=2`, `connection-timeout` 짧게(예 5000ms — 전용이라 즉시 확보 예상, fail-fast).

### 4.2 `CancelEventOutboxRepositoryImpl` (수정)
- 생성자에 `CancelOutboxJdbcTemplate`(전용) 추가 주입. 기존 `CancelEventOutboxJpaRepository`(메인) 유지.
- `insertPending` → `jpaRepository.insertPendingIdempotent(...)` (변경 없음, 메인 풀/TX3).
- `findPendingBatch` → 전용 JdbcTemplate SELECT + RowMapper(id, cancel_request_id, payload). 기존 `ORDER BY created_at ASC LIMIT` 동등.
- `markPublished(List<Long>)` → 전용 JdbcTemplate `UPDATE ... SET status='PUBLISHED', published_at=... WHERE id IN (...)`. 빈 리스트 no-op 유지.
- 배선: `CancelEventOutboxRepositoryImpl`은 OUTBOX 모드에서만 실제 배수 메서드가 호출되므로, 전용 JdbcTemplate 의존은 OUTBOX 전용 빈. INLINE 모드에서 이 repo 빈이 생성되더라도 배수 메서드는 호출되지 않는다. (구현 시: repo 빈을 OUTBOX-gated로 하거나, 전용 JdbcTemplate을 `ObjectProvider`로 지연 주입해 INLINE에서 안전. 계획 단계에서 기존 `PersistenceConfig` 배선과 정합하게 확정.)

### 4.3 무변경
- `CancelEventOutboxPublisher`(폴러) — repo 포트 그대로 호출, 내부 배선 변경 인지 불필요.
- `OutboxCancelEventPublisher`(insertPending 호출) — 그대로.
- 메인 DataSource / EntityManager / TxManager — 무변경.

## 5. 데이터 흐름 (Data Flow)

```
[취소] CancelTxWriter.saveTx3 (TX3, 메인 풀)
        → outboxRepo.insertPending → JPA(메인 풀) → outbox 행 INSERT (TX3와 원자 커밋)

[폴러] CancelEventOutboxPublisher.publish (scheduling-1 스레드)
        → outboxRepo.findPendingBatch → 전용 JdbcTemplate(전용 풀 2) → PENDING 조회
        → kafkaTemplate.send (비동기 병렬, Kafka)
        → outboxRepo.markPublished  → 전용 JdbcTemplate(전용 풀 2) → PUBLISHED 표시  ← 더는 굶지 않음
```

## 6. 불변식·에러 (Invariants & Error Handling)

- **send-then-mark 유지** — 발행 성공분만 markPublished. mark-before-send 금지(= 발송 실패 시 이벤트 유실).
- **insertPending 원자성** — 메인 풀/TX3 유지(§3.1).
- **전용 JdbcTemplate은 문장당 auto-commit** — 전용 TransactionManager 불필요(single SELECT / single UPDATE).
- **전용 풀도 실패하면?** 경합 ≈ 0라 사실상 없으나, 만약 커넥션 실패 시 기존과 동일하게 예외 → 해당 배치 PENDING 잔존 → 다음 폴 재시도(at-least-once). 라이브락과 달리 전용 풀은 즉시 회복.
- **per-row 발행 실패 격리** — 기존 유지(성공 id만 수집).

## 7. 대안 검토 (Alternatives Considered)

| 대안 | 라이브락 해소 | 기각 이유 |
|---|---|---|
| **전용 DataSource (채택)** | ✅ | 커넥션 격리, ~무비용 |
| 2nd EntityManagerFactory | ✅ | native 쿼리 3개에 과임(@Primary·엔티티스캔 분리). JdbcTemplate이 단순 |
| 수직 스케일업(DB 2→4, 풀 확대) | ⚠️ 지연만 | 풀 커져도 **공유** → 더 높은 포화점서 폴러 또 굶음. 지속 과금 |
| 수평 스케일아웃(인스턴스↑) | ❌ | 분산락 → 폴러 1개·공유 풀 그대로, DB 병목 가중 |
| 풀 크기만 확대 | ❌ | 2코어 DB 공식 상한(≈5) — 늘리면 DB 스래싱 |

**통찰:** 라이브락은 contention(격리) 문제지 quantity(양) 문제가 아니다 → 스케일링이 아니라 격리로 답한다.

## 8. 테스트 (Testing)

- **IT(Testcontainers)**: `findPendingBatch`·`markPublished`가 전용 JdbcTemplate 경유로 동작 / `insertPending`은 JPA(메인) 경유 유지 / 배치 표시·빈 리스트 no-op. 기존 `CancelEventOutboxRepositoryIT` 확장.
- **스케줄러 IT**: 기존 `CancelEventOutboxPublisherIT` 3종(발행·락미스 skip·per-row 격리) 그대로 통과(포트 불변).
- **빈 로딩**: OUTBOX 모드에서 전용 DataSource/JdbcTemplate 빈이 뜨고, INLINE 모드에서 컨텍스트가 깨지지 않음.
- payment 전체 스위트 회귀 green.

## 9. 검증 — AWS 재측정 (Verification)

런북 [`publish-pattern-benchmark.md`](../../load-test/publish-pattern-benchmark.md) 절차. OUTBOX `poll=1s`, stress 50→400 VU, obs OOM 방지로 PROM 끄고 outbox는 payment_db 직접 샘플링.

**판정 (라이브락 해소):**
- PENDING이 **낮게 유지**(부하 중 폭증 없음) — naive 79.8k~101k 대비.
- `order_processed`가 **실제 취소수에 수렴**(1,000에 갇히지 않음).
- `producer_send_total`이 취소수에 **근접**(재발송 폭주 424k 소멸).

남는 한계(단일 스레드 배수율)가 확인되면 → CDC/병렬화 후속.

## 10. 열린 결정 / 후속

- 전용 풀 크기 2 vs 3 — 단일 스레드 순차라 2로 시작(계획서 확정).
- INLINE 모드에서의 repo 빈 배선 방식(OUTBOX-gate vs ObjectProvider) — 계획 단계에서 기존 배선과 정합하게 확정.
- CDC / 배수 병렬화 — 별도 후속.
