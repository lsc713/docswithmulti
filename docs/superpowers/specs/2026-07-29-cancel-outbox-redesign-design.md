# 결제 취소 이벤트 발행 재기획 — Transactional Outbox 정식화 (설계)

**작성:** 2026-07-29
**상태:** 설계 확정 (구현 대기)
**범위:** payment-service `payment.cancelled` 발행 경로. (request_hash/멱등성 재구성은 별도 스펙 — 후속.)

---

## 1. 문제 (Problem)

`CancelTxWriter.saveTx3`는 TX3 마지막에 `CancelEventPublisher.publish()`로 `payment.cancelled`를 발행한다. 기본 구현 `InlineCancelEventPublisher`는 `kafkaTemplate.send(...).get(5s)`를 **TX3 트랜잭션 안에서 동기 호출**한다 — 전형적 **dual-write**:

- DB 커밋 성공 + Kafka send 유실 → 이벤트 영구 유실 (재발행 원천 없음)
- Kafka send 성공 + 커밋 실패 → 롤백된 취소에 phantom 이벤트

`cancel.publish.mode`로 3구현이 포트(`CancelEventPublisher`) 뒤에 있으나 **기본이 `INLINE`(`matchIfMissing=true`)** — 즉 정답(`OUTBOX`)을 만들어 두고도 프로덕션 기본은 dual-write다.

기존 `OUTBOX` 구현은 정공법에 가깝다(원자적 write, 삽입 멱등, relay at-least-once, per-row 격리, 파티션키 cancelRequestId). 하지만 **정식 기본이 아니고**, 운영 갭 2개(poison 무한 재시도, 테이블 무한 증식)가 있다. 마이그레이션 이력(V3 outbox → V10 failed_kafka_event → V11 inline → V12 outbox 재생성)이 이 churn을 보여준다.

## 2. 목표 / 비목표

**목표**
- dual-write 제거 — `payment.cancelled` 발행을 트랜잭셔널 아웃박스로 정식화.
- 저지연 — 이벤트 드리븐 wake로 평상시 지연 ≈ Kafka RTT.
- 운영 안전 — poison 이벤트 격리, 아웃박스 테이블 보존 관리.
- at-least-once 유지 (poll backstop이 정합 보장).

**비목표 (이번 스코프 밖)**
- request_hash/멱등성 재구성 (별도 스펙 — 후속).
- 실 Toss url/Basic 인증 (배포 config, 별건).
- order-service 컨슈머 코드 변경 (단, 멱등 수렴은 **검증**한다 — §7).
- `INLINE`/`INLINE_ASYNC` 제거 (벤치·학습 자산으로 유지).

## 3. 설계 결정

- **D1 — OUTBOX 정식 기본.** `cancel.publish.mode` 기본값을 `OUTBOX`로 이동(`matchIfMissing`을 OUTBOX 구현에). `INLINE`/`INLINE_ASYNC`는 명시 설정 시에만 활성(비교/학습).
- **D2 — 이벤트 드리븐 wake (비권위, 지연 최적화).** `OutboxCancelEventPublisher.publish`가 `insertPending` 후 `TransactionSynchronization.afterCommit`에서 Redisson `RTopic.publish(wake)`. relay 리더가 RTopic 구독 → 즉시 폴(coalesced). **`@Scheduled` poll backstop 유지 = 정합 보장 불변식.** wake 유실/Redis 장애 시 poll이 커버 → dual-write 아님 (write는 outbox 행 1개만 권위).
- **D3 — poison 재시도 관리.** `cancel_event_outbox`에 `retry_count`, `last_error`, status `DEAD` 추가(V14). relay가 send 실패 시 `retry_count++`+`last_error`; `retry_count ≥ MAX`(`cancel.outbox.max-retries`, 기본 **10**)면 `DEAD` 전이 + `OperationAlertPort` 알림. per-row 격리 유지.
- **D4 — 보존/purge.** relay와 **분리된 별도 `@Scheduled` 메서드**(자체 RLock, `fixedDelay` 기본 1일): `status='PUBLISHED' AND published_at < now - retention-days` 삭제. `cancel.outbox.retention-days` 기본 7.
- **D5 — at-least-once ⟶ 컨슈머 멱등 계약 명시.** outbox는 중복 발행 가능 → order-service 컨슈머가 멱등/수렴해야 정합 성립 (§7 검증).

## 4. 아키텍처 / 데이터 흐름

```
[취소 API] → TX1(PENDING) → risk → TX2(PROCESSING) → PG cancel → TX3 {
    PaymentItem/Payment/CancelRequest(COMPLETED) 원자 저장
    + cancel_event_outbox INSERT(PENDING)   ← 같은 커밋 (원자적, 단일 권위 write)
}  ──afterCommit──▶ RTopic.publish("cancel-outbox-wake")   (비권위 힌트)

[relay 리더 (RLock, OUTBOX 모드만)]
   트리거: RTopic wake(coalesced) 또는 @Scheduled backstop(poll-ms)
   1) findPendingBatch(LIMIT N, created_at ASC, status=PENDING)
   2) 전건 kafkaTemplate.send 파이프라이닝 → ack 일괄 대기(30s)
   3) 성공분: markPublished (PUBLISHED, published_at)
      실패분: retry_count++/last_error; retry_count≥MAX → DEAD + alert

[purge 잡 (자체 락)]
   DELETE status=PUBLISHED AND published_at < now - retention-days
```

**컴포넌트 (기존 재사용/수정)**
- `OutboxCancelEventPublisher` — publish에 afterCommit wake 추가.
- `CancelEventOutboxPublisher`(relay) — RTopic 구독 + coalesce + retry_count/DEAD/alert.
- `CancelEventOutboxRepositoryImpl` — retry/last_error/dead 쿼리, findPendingBatch는 `status='PENDING'` 유지(DEAD 제외), purge 쿼리.
- `OperationAlertPort` — 기존 포트 재사용(DEAD 알림).
- 신규: purge 스케줄러(또는 relay 내 별도 @Scheduled).
- Flyway **V14** — outbox 컬럼 추가.

## 5. 스키마 (Flyway V14)

```sql
ALTER TABLE cancel_event_outbox
    ADD COLUMN retry_count INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error  VARCHAR(500) NULL;
-- status 는 기존 VARCHAR(20): PENDING | PUBLISHED | DEAD (값 규약만 확장, 스키마 변경 없음)
```

기존 UK(cancel_request_id)·INDEX(status, created_at) 유지. DEAD 조회용으로 status 인덱스가 이미 커버.

## 6. 실패 매트릭스 (정합 불변)

| 실패 지점 | 결과 | 정합 |
|-----------|------|------|
| TX3 커밋 실패 | outbox 행도 롤백(같은 tx) | 취소·이벤트 모두 없음 (원자) |
| afterCommit wake 발사 실패/유실 | 지연만 poll 주기로 | 무손실 (poll backstop) |
| Redis 장애 | wake 불가, poll만 | 무손실, 지연↑ |
| relay send 실패 | 행 PENDING 유지 + retry_count++ | 다음 트리거 재발행 (at-least-once) |
| send 성공, markPublished 실패 | 다음 폴 재발행(중복) | 컨슈머 멱등이 흡수 (D5) |
| 영구 발행불가(poison) | MAX 초과 → DEAD + alert | 격리, 무한재시도/로그폭주 방지 |

## 7. 전제 계약 검증 (D5)

outbox at-least-once의 정합은 **order-service 컨슈머 멱등/수렴**에 의존. CLAUDE.md는 "order 컨슈머가 전체 아이템 재계산 + 주문 행 락 → 순서무관 수렴"이라 명시. → 구현 전/중 **order-service 컨슈머가 실제로 (a) 중복 이벤트에 멱등, (b) 순서무관 수렴**하는지 코드 확인. 미충족 시 별도 이슈로 승격(이 스코프에선 발행측만 다루되 갭을 가시화).

## 8. 테스트 전략

- **단위:** `OutboxCancelEventPublisher` afterCommit wake 등록(트랜잭션 동기화 mock); relay의 send 성공→PUBLISHED / 실패→retry_count++ / MAX→DEAD+alert 분기; purge 쿼리 경계.
- **통합(Testcontainers 실 MySQL + embedded Kafka 또는 KafkaTemplate mock):** TX3 커밋과 outbox 행 원자성(커밋 롤백 시 행 없음); relay가 PENDING 배치를 발행하고 PUBLISHED 전이; poison이 DEAD로 격리.
- **at-least-once:** markPublished 전 크래시 시뮬레이션 → 재발행 → (컨슈머 멱등 전제) 최종 1회 반영.
- 기존 `ProcessingRecoveryOutboxIT` 등 회귀 green 유지. 기본 모드 전환으로 깨지는 기존 INLINE 전제 테스트 점검.

## 9. 롤아웃

- 기본 모드 전환은 config 한 줄이나, **기존 INLINE 전제 테스트/문서(CLAUDE.md, kafka-design.md)** 를 OUTBOX 기준으로 정정 필요. CLAUDE.md의 "TX3 인라인 발행" 서술을 "OUTBOX 정식 + 이벤트 wake"로 갱신.

## 10. 미해결/후속

- request_hash/멱등성 재구성 (별도 스펙).
- order-service 컨슈머 멱등 검증 결과에 따른 후속 이슈.
- 실 Toss url/인증(배포 config).
