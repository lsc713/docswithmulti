# Kafka 발행 패턴 실측 비교 — 설계 (Design)

**작성일:** 2026-07-11
**상태:** 승인됨 (brainstorming) → 계획(writing-plans) 대기

## 1. 목적 (Purpose)

`payment.cancelled` 이벤트 발행 방식 3가지를 **같은 AWS load-test 리그에서 실측 비교**한다. 취소 처리량/커넥션 점유·발행→소비 지연·Kafka 장애 시 거동·발행 버스트를 한 판에 드러내, "동기 인라인 발행이 Kafka의 이점을 얼마나/무엇을 맞바꾸는가"를 정량화한다. 프로젝트의 measure→hypothesize→measure 방법론과 기존 리그를 재사용한다.

배경: 현재 `payment.cancelled`는 TX3 안에서 `kafkaTemplate.send().get(5s)` 동기 인라인 발행이다(dual-write, 실패 시 TX3 롤백 → processing-recovery). 인라인의 `send().get()`이 **TX3 커넥션 점유시간에 브로커 RTT를 더한다** — 점유시간이 풀 처리량을 지배함은 앞선 측정(rps 147→220, 커밋 6→4)에서 증명됐다. 이 실험은 그 연장선이다.

## 2. 범위 (Scope)

**In scope (이 스펙):**
- `payment.cancelled` 발행 모드 런타임 토글 (3모드: INLINE / INLINE_ASYNC / OUTBOX)
- payment용 outbox 테이블 + 발행 스케줄러 (merchant-limit 패턴 복사)
- 발행 비교용 계측 + 전용 Grafana 대시보드
- P1(처리량/점유) + P2(지연/장애/버스트) 측정 계획

**Out of scope (후속 스펙):**
- **P3 — Debezium CDC(outbox 로그 테일링).** 인프라 부담(Kafka Connect + binlog ROW/GTID)이 커 별도 스펙. P1/P2 결과가 동기를 주면 로컬 PoC부터.
- 폴 주기 스윕(10s vs 1s)은 **env 노브**로 처리 — 별도 빌드 아님.

## 3. 사용자 스토리 (User Story)

시스템 소유자(학습·블로그 목적)로서, `payment.cancelled`의 세 발행 방식을 같은 부하 판에서 재현 가능하게 전환·측정해, 각 방식이 처리량·지연·가용성·부하모양을 어떻게 맞바꾸는지 관측된 사실로 확인하고 블로그 3막 자료로 남기고 싶다.

## 4. 아키텍처 (Architecture)

### 4.1 발행 모드 토글 (런타임, 같은 바이너리)

설정 `cancel.publish.mode` (enum, 기본 `INLINE`). 프로젝트의 기존 opt-in 토글 패턴(`OTEL_JAVAAGENT`, `LOADTEST_QUERYCOUNT_ENABLED`)과 동일하게 **플래그만 바꿔 재배포 없이 재측정**한다.

| 모드 | 동작 | 안전성 |
|---|---|---|
| `INLINE` (기본, 현행) | `CancelTxWriter` TX3 안 `send().get(5s)`. **무변경.** | dual-write 안전(실패시 롤백) |
| `INLINE_ASYNC` | TX3 안 `send()` fire-and-forget(`.get()` 없음, 실패 롤백 없음) | **안전하지 않음** — 측정 전용 상한 레퍼런스 |
| `OUTBOX` | TX3가 `cancel_event_outbox` 행 INSERT(같은 커밋) + 별도 폴러가 발행 | dual-write 완전 해소 |

- 발행 전략은 인터페이스로 분리(`CancelEventPublisher` 전략), 모드에 따라 주입/선택.
- `INLINE_ASYNC` 활성 시 애플리케이션 기동 로그에 WARN("측정 전용, 프로덕션 사용 금지").

### 4.2 신규 테이블 `V10__create_cancel_event_outbox.sql`

`limit_event_outbox` 미러:

```sql
CREATE TABLE cancel_event_outbox (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    cancel_request_id BIGINT      NOT NULL,
    payload           JSON        NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cancel_event_outbox_request (cancel_request_id),
    INDEX idx_cancel_outbox_status_created_at (status, created_at)
);
```

- **집계 키 = cancel_request_id** (정정된 파티션 키와 일치; `CancelTxWriter`가 그 값을 Kafka 키로 씀).
- `cancel_request_id` **UK** — 복구 재실행 시 중복 outbox 행 방지(멱등 INSERT).

### 4.3 발행 스케줄러 `CancelEventOutboxPublisher`

`OutboxPublisherScheduler`(merchant-limit) 패턴 복사:
- `@Scheduled(fixedDelay = ${cancel.outbox.poll-ms:10000})`.
- Redis 분산락(`setIfAbsent` + TTL)로 **한 인스턴스만** — 이미 분산락 관례 존재.
- `findPendingBatch(batchSize)` → 건당 `kafkaTemplate.send(topic, cancelRequestId, payload).get()` → `markPublished`.
- 실패는 로깅 후 다음 폴에 재시도(at-least-once).

### 4.4 계측 (common-observability / payment / order)

- **발행→소비 e2e 지연**: order 컨슈머가 `Duration(now - payload.cancelledAt)`를 Micrometer Timer `cancel.event.e2e.latency`로 기록(페이로드에 `cancelledAt` 이미 존재).
- **Kafka client 메트릭**: `KafkaClientMetrics` 바인딩 → producer send rate(버스트)·consumer lag.
- 취소 rps·Hikari active/pending·TX3 점유시간은 기존 지표 재사용(saturation/system-views).
- 모든 계측은 opt-in 유지(평상시·CI 영향 0) — 기존 관측 정책 준수.

### 4.5 전용 대시보드 `publish-pattern-comparison.json`

행:
1. **취소 처리량/점유** — rps, p95, Hikari active/pending, TX3 hold time
2. **발행→소비 지연** — `cancel.event.e2e.latency` p50/p95
3. **consumer lag** — order 컨슈머 그룹 lag
4. **produce rate 시간축** — 버스트 가시화(outbox 봉우리 vs inline 매끄러움)
5. **장애 주입 시 취소 성공률** — Kafka down 구간

## 5. 데이터 흐름 (Data Flow)

```
INLINE (현행):
  취소 → TX3[DB 쓰기 + send().get()] → 커밋 → order 소비
         send 실패 → TX3 롤백 → processing-recovery 재실행

INLINE_ASYNC (측정 전용):
  취소 → TX3[DB 쓰기 + send() fire-and-forget] → 커밋 → order 소비
         send 실패 → (감지 못 함, 이벤트 유실 가능) ← 안전하지 않음

OUTBOX:
  취소 → TX3[DB 쓰기 + cancel_event_outbox INSERT] → 커밋 (원자)
       → CancelEventOutboxPublisher(폴러) → send().get() → markPublished → order 소비
         폴러 실패 → 다음 폴 재시도(at-least-once, 중복 가능 → order dedup)
```

## 6. 정합성·에러 처리 (Correctness & Error Handling)

- **OUTBOX at-least-once 보존**: outbox 행이 TX3와 **원자 커밋** + order 컨슈머의 `cancelRequestId` dedup(기존 `ProcessedCancelEventRepository`) → 이중 처리 없음. 중복 발행돼도 소비자 멱등.
- **processing-recovery 상호작용 (핵심 미묘함):**
  - INLINE/INLINE_ASYNC: 복구가 TX3 재실행 시 재발행(현행).
  - OUTBOX: TX3는 outbox 행만 쓰고 발행은 폴러가 분리 담당. 커밋된 취소는 COMPLETED라 복구 대상 아님. **미커밋(outbox 행 없음)만** 복구가 재실행 → `cancel_request_id` UK로 멱등 INSERT.
  - **복구 경로가 모드를 인지**해야 한다(outbox 모드에서 인라인 재발행하지 않음). 통합테스트로 모드별 복구 검증. (백로그 "인라인 팬텀 창을 복구가 닫나"와 연결.)
- **INLINE_ASYNC**: 의도적 dual-write 구멍. 플래그 뒤 격리, 기본값 아님, 기동 WARN, 문서에 "프로덕션 불가" 명시.

## 7. 측정 계획 (Measurement Plan)

**공정 비교 토글**(커밋 6→4 런과 동일): OTel OFF · query-count OFF · tomcat 지표 ON · 동일 DB(m7g.large) · 동일 시드(100k) · 동일 VU 스윕(50→400).

### P1 — 처리량/점유 (한 런에 3모드)
- 각 모드 취소 rps + p95 + Hikari hold time 캡처.
- **가설**: `outbox ≥ inline` (브로커 RTT/hold ≈ 5~8% 이득) · `inline_async ≥ outbox` (상한). 델타는 커밋 6→4의 ×1.5가 아니라 **소소(~수%)**할 것 — 브로커가 로컬이라 RTT가 hold의 작은 비율.
- general_log로 **커밋 수 불변** 확인(outbox INSERT는 TX3 같은 커밋에 얹혀 추가 fsync 0).

### P2 — 지연/장애/버스트
- **e2e 지연**: `cancel.event.e2e.latency` — inline ~ms vs outbox ~폴 주기(≤10s).
- **장애 주입**: 런 중간 infra 노드 Kafka **단일 브로커 컨테이너 `docker stop`**(SSM 경유) → 취소 성공률(inline 급락 vs outbox 유지) → `docker start` 시 outbox 백로그 배수.
- **버스트**: produce rate 시간축 — outbox 폴 주기마다 봉우리 vs inline 매끄러움.
- **폴 노브 선택 재런**: `cancel.outbox.poll-ms` 10000→1000으로 outbox 재런, 지연↓/빈폴링 부하↑ 트레이드 표시.

**산출**: 3모드 × (처리량·지연·장애·버스트) 비교표 + 대시보드 캡처. 블로그 3막 자료.

## 8. 테스트 (Testing)

- **단위**: 모드 스위치가 올바른 전략 선택 / OUTBOX가 outbox INSERT를 TX3 안에서 / INLINE_ASYNC가 블록·롤백 안 함 / 기존 INLINE 회귀.
- **통합(Testcontainers)**: OUTBOX → 취소 → outbox 행 PENDING → 폴러 → Kafka 메시지 발행 + 행 PUBLISHED · order 컨슈머 dedup 유지 · **모드별 processing-recovery** 경로(미커밋 재실행 시 UK 멱등).
- **AWS 전 로컬 스모크**: 3모드 각각 소량 부하로 경로 확인 후 AWS 런.

## 9. 열린 결정 / 후속

- P3 Debezium CDC — 별도 스펙(P1/P2 동기 시).
- 계측 모듈 위치(common-observability vs payment 로컬)는 계획 단계에서 기존 구조 따라 확정.
