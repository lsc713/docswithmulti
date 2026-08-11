# Cancel Outbox Redrive Worker 설계

## 목표

이슈 #107은 durable `REQUESTED` redrive 작업을 단일 worker만 획득해 실행 직전 안전성을 다시 판정하고, 필요한 경우 원본 취소 이벤트를 Kafka에 재발행한 뒤 주문과 재고의 정상 수렴을 확인해 최종 상태를 기록한다.

이번 범위는 정상 성공과 안전한 무발행 종결에 집중한다. Kafka 발행 실패, downstream 조회 장애, 60초 미수렴, 재시작 복구와 실행 동시성 상한은 후속 이슈 #108에서 `FAILED/PUBLISH`, `FAILED/CONVERGENCE` 정책과 함께 처리한다.

## 범위

### 포함

- `REQUESTED → REDRIVING` CAS 획득
- 실행 직전 기존 inspection use case 재호출
- `RESOLVED_ALREADY_APPLIED`, `REJECTED` 무발행 종결
- `CancelEventReplayPort`와 Kafka broker ACK adapter
- 원본 `cancelRequestId` key와 원본 payload 재사용
- broker ACK metadata 저장
- 2초 DB polling 기반 수렴 확인
- 60초 정상 수렴 관찰 구간
- 부분 수렴 대기와 추가 발행 금지
- `RESOLVED` 전후 상태와 Kafka 결과 감사 저장
- order/product 중복 소비 멱등 회귀 테스트
- 운영자 CLI 예제

### 제외

- `FAILED/PUBLISH`, `FAILED/CONVERGENCE` 전이
- `UNKNOWN`, 발행 예외, 60초 초과 작업의 최종 실패 처리
- 프로세스 장애 후 `REDRIVING` 작업 재획득
- 최대 동시 실행 5개 제한과 전용 executor
- 구조화 로그·운영 메트릭·실패 runbook
- 새로운 downstream ACK 이벤트

## 선택한 접근

DB 상태 기반의 두 단계 polling을 사용한다.

1. dispatcher가 `REQUESTED` 작업을 조회하고 CAS로 하나씩 획득한다.
2. 획득한 worker가 재검사하고 필요할 때 한 번만 Kafka에 발행한다.
3. broker ACK 직후 기존 `result`와 `before_state`에 발행 결과를 저장한다.
4. convergence poller가 `REDRIVING AND result IS NOT NULL`인 작업을 2초마다 재검사한다.
5. 두 레그가 모두 적용됐을 때만 CAS로 `RESOLVED` 처리한다.

이 방식은 worker thread를 sleep으로 점유하지 않는다. ACK 결과와 수렴 대기 여부가 DB에 남으므로 프로세스 재시작 뒤에도 #108의 복구 worker가 판단할 근거를 가진다. 별도 `next_check_at` 열은 단건 수동 redrive 범위에서 필요하지 않으므로 추가하지 않는다.

## 상태와 저장 표식

| DB 상태 | `result` | 의미 | #107 동작 |
| --- | --- | --- | --- |
| `REQUESTED` | null | 아직 미획득 | dispatcher CAS 대상 |
| `REDRIVING` | null | 획득 후 재검사 또는 발행 중 | 소유 worker만 처리; 장애 시 #108 대상 |
| `REDRIVING` | Kafka ACK JSON | 발행 완료, 수렴 대기 | convergence poll 대상 |
| `RESOLVED_ALREADY_APPLIED` | 무발행 결과 JSON | 실행 전 이미 수렴 | 종결 |
| `REJECTED` | null | 안전 조건 불충족 | 종결 |
| `RESOLVED` | Kafka ACK JSON | 발행 후 전체 수렴 | 종결 |

원본 `cancel_event_outbox`의 상태와 payload는 어떤 경로에서도 수정하지 않는다.

## 컴포넌트

### Repository 확장

`CancelOutboxRedriveRepository`는 다음 원자 연산을 제공한다.

- `findRequestedIds(limit)`: 오래된 요청부터 ID 조회
- `tryStart(redriveId, startedAt)`: `WHERE status='REQUESTED'` CAS
- `recordPublished(redriveId, beforeState, result)`: `REDRIVING AND result IS NULL` 조건부 ACK 저장
- `findConverging(startedAfter, limit)`: `REDRIVING AND result IS NOT NULL AND started_at >= startedAfter` 조회
- `resolveAlreadyApplied(...)`: `REDRIVING` 조건부 무발행 종결
- `reject(...)`: `REDRIVING` 조건부 거부 종결
- `resolve(...)`: `REDRIVING` 조건부 성공 종결

모든 상태 update는 affected row가 1일 때만 성공이다. 여러 인스턴스가 같은 작업을 조회해도 CAS winner 하나만 재검사·발행한다. 여러 convergence poller가 같은 작업을 검사할 수 있지만 terminal update는 하나만 성공한다.

### Worker application service

worker는 `CancelOutboxInspectionUseCase`, `CancelOutboxSourcePort`, `CancelEventReplayPort`, redrive repository, JSON snapshot encoder와 `Clock`에 의존한다.

`start(redriveId)` 흐름:

1. `tryStart`가 false면 즉시 반환하고 inspection과 replay를 호출하지 않는다.
2. 작업과 원본을 조회한다.
3. inspection 결과를 안정된 JSON으로 직렬화해 실행 전 snapshot을 만든다.
4. 판정별로 처리한다.

| inspection decision | 처리 |
| --- | --- |
| `ALREADY_APPLIED` | 발행 없이 `RESOLVED_ALREADY_APPLIED`; before/after에 동일 snapshot 저장 |
| `NOT_ELIGIBLE` | 발행 없이 `REJECTED`; `last_error`에 reason code 저장 |
| `REDRIVE_REQUIRED` | 원본 key/payload로 replay 후 ACK와 before snapshot 저장 |
| `UNKNOWN` | 발행·종결 없이 `REDRIVING/result=null` 유지; #108 처리 |

`INCONSISTENT_DOWNSTREAM_STATE`는 inspection이 `NOT_ELIGIBLE`과 해당 reason code로 반환하므로 `REJECTED` 처리한다.

원본 outbox가 worker 실행 사이에 사라지는 등 예외가 발생하면 이번 범위에서는 안전하게 발행하지 않고 예외를 scheduler 경계까지 전달한다. scheduler는 안전한 식별자만 로그에 남기고 작업을 `REDRIVING/result=null`로 유지한다.

### Replay port와 Kafka adapter

```java
ReplayResult replay(long cancelRequestId, String payload);

record ReplayResult(String topic, int partition, long offset) {}
```

adapter는 `KafkaTemplate.send(topic, String.valueOf(cancelRequestId), payload)`의 future를 최대 5초 동안 기다린다. 성공 시 broker `RecordMetadata`를 `ReplayResult`로 변환한다. payload를 deserialize/re-serialize하지 않으며 입력 문자열을 그대로 send에 전달한다.

ACK JSON 형식은 다음으로 고정한다.

```json
{"topic":"payment.cancelled","partition":0,"offset":123}
```

발행 timeout과 예외는 안전한 replay exception으로 전달하며 #107에서 `FAILED`로 바꾸지 않는다.

### Dispatcher

OUTBOX mode에서만 활성인 infrastructure scheduler다.

- 기본 1초 fixed delay
- 한 poll에서 오래된 `REQUESTED` 최대 100개 조회
- 각 ID를 worker `start`에 전달
- CAS 실패는 정상 경합으로 간주
- 개별 작업 예외가 다음 ID 처리를 막지 않도록 작업별로 격리

이번 이슈는 기본 동시 실행 5개 제한을 구현하지 않으므로 dispatcher는 scheduler thread에서 순차 처리한다. 이는 v1 단건 수동 요청 범위와 일치한다.

### Convergence poller

- 기본 2초 fixed delay
- `Clock.instant().minusSeconds(60)` 이후 시작된, ACK가 저장된 `REDRIVING` 작업 최대 100개 조회
- 각 작업을 inspection use case로 재검사
- `ALREADY_APPLIED`일 때만 `RESOLVED`와 after snapshot 저장
- `REDRIVE_REQUIRED`는 부분 또는 미수렴 상태이므로 변경 없이 다음 poll 대기
- `UNKNOWN` 또는 `NOT_ELIGIBLE`은 이번 이슈에서 종결하지 않음
- `started_at`이 60초보다 오래된 작업은 더 이상 #107 poll 대상에 포함하지 않고 #108이 실패 종결

poller는 replay port를 의존하거나 호출하지 않는다. 따라서 부분 수렴 중 추가 발행이 구조적으로 불가능하다.

## JSON 감사 계약

### 상태 snapshot

inspection 결과를 다음 필드 순서와 enum 문자열로 저장한다.

```json
{
  "decision":"REDRIVE_REQUIRED",
  "reasonCode":null,
  "order":{"status":"APPLIED","evidence":[]},
  "stock":{"status":"NOT_APPLIED","evidence":[]}
}
```

원본 payload와 payment key는 snapshot에 포함하지 않는다. evidence는 기존 `CancelRestoreLegSnapshot`의 target ID, 현재 상태와 수량만 포함한다.

### terminal 저장

- `RESOLVED_ALREADY_APPLIED`: before/after에 동일한 `ALREADY_APPLIED` snapshot, result에 `{"outcome":"ALREADY_APPLIED"}`
- `REJECTED`: before/after에 동일한 inspection snapshot, `last_error`에 reason code
- `RESOLVED`: before에 발행 전 snapshot, after에 최종 `ALREADY_APPLIED` snapshot, result에 Kafka ACK JSON

기존 GET API의 `result`, `beforeState`, `afterState` 타입은 문자열에서 JSON 객체로 보정한다. presentation mapper가 유효한 DB JSON 문자열을 `JsonNode`로 변환하므로 클라이언트는 `result.topic`, `beforeState.order.status`처럼 직접 조회할 수 있다. null 필드는 계속 명시적 JSON null로 반환하며 원본 payload와 payment key는 노출하지 않는다.

## 실패 경계

이번 PR이 일시적으로 남길 수 있는 활성 상태를 명시한다.

- Kafka send timeout/exception: `REDRIVING`, `result=null`
- preflight `UNKNOWN`: `REDRIVING`, `result=null`
- 발행 뒤 조회 장애·불일치: `REDRIVING`, `result!=null`
- 60초 미수렴: `REDRIVING`, `result!=null`, #107 poll 대상 제외
- worker crash: 마지막 커밋 상태 유지

이 상태들은 #108의 scan·retry·FAILED 전이 대상이다. #107은 오류를 성공 또는 거부로 오분류하지 않는다.

## 테스트 전략

모든 변경은 Red → Green → Refactor로 구현한다.

1. repository MySQL integration test
   - 두 CAS 요청 중 affected row 1개
   - ACK 저장과 convergence 조회
   - terminal update CAS와 전체 non-null row mapping
2. replay adapter unit test
   - 원본 key/payload 동일성
   - broker ACK topic·partition·offset 변환
   - bounded timeout/exception 전달
3. worker unit test
   - CAS loser의 inspection/replay 0회
   - already applied와 rejected 무발행 종결
   - required의 정확히 한 번 replay 및 ACK 저장
   - UNKNOWN의 무발행·비종결
4. convergence poller/worker test
   - 양쪽 APPLIED에서 한 번만 RESOLVED
   - 양쪽 부분 수렴 조합에서 변경 없음·replay 0회
   - 60초 범위 밖 작업 미조회
5. scheduler test
   - 작업별 예외 격리
   - 설정된 poll 주기와 batch 전달
6. Kafka + MySQL integration test
   - 원본 key/payload로 broker ACK 저장 후 RESOLVED
7. order/product consumer 멱등 회귀 테스트
   - 기존 처리 레그의 상태·수량이 중복 이벤트로 추가 변경되지 않음
8. CLI 문서 검증
   - inspect → request → status polling 명령과 정상 최종 출력
9. 상태 조회 controller 계약 테스트
   - result와 전후 상태가 이중 인코딩된 문자열이 아니라 JSON 객체
   - null 감사 필드는 기존 계약대로 명시적 null

## 운영 CLI

`docs/operations/cancel-outbox-redrive.md`에 다음 순서를 문서화한다.

1. 관리자 헤더로 source outbox 검사
2. 사유와 함께 redrive 요청
3. 반환된 redrive ID로 2초 간격 상태 조회
4. `RESOLVED`, `RESOLVED_ALREADY_APPLIED`, `REJECTED` 결과 해석
5. `REDRIVING`이 60초를 넘으면 #108 실패 runbook이 추가되기 전까지 수동 조사 대상으로 표시

## 완료 조건

- CAS winner 하나만 inspection과 replay를 호출한다.
- 이미 수렴하거나 안전하지 않은 이벤트는 Kafka 발행 없이 감사 가능한 terminal 상태가 된다.
- 유효한 미수렴 이벤트는 원본 key와 payload로 한 번만 broker ACK를 받는다.
- 부분 수렴에서는 추가 발행이나 조기 완료가 없다.
- 두 레그가 60초 안에 모두 적용되면 `RESOLVED`와 전후 상태, Kafka metadata가 조회된다.
- 원본 DEAD outbox는 변경되지 않는다.
- 기존 order/product 중복 소비 멱등 테스트가 통과한다.
