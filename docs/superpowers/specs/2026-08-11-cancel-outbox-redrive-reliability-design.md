# Cancel Outbox Redrive Reliability 설계

## 목표

이슈 #108은 #107에서 의도적으로 `REDRIVING`에 남겨 둔 실패와 crash window를 안전하게 종결하고, 운영자가 실패 위치를 구분해 새 작업으로 재요청할 수 있게 한다. 동시에 payment-service 인스턴스별 실제 redrive 실행을 기본 5개로 제한하고, 다중 인스턴스의 중복 scan에서도 한 작업의 발행자는 DB CAS 승자 하나로 유지한다.

이번 설계는 기존 `cancel_outbox_redrive` 상태와 감사 필드를 확장해 사용한다. 클러스터 전역 slot/lease 테이블은 추가하지 않는다.

## 승인된 정책

- 발행 전 inspection의 `UNKNOWN`은 `FAILED/PUBLISH`로 분류한다.
- `last_error=PREFLIGHT_UNKNOWN`으로 Kafka send 실패와 구분한다.
- `REDRIVING AND result IS NULL` 상태가 60초 이상 정체되면 `FAILED/PUBLISH`로 종결한다.
- ACK 저장 전 crash가 의심되는 작업은 같은 ID로 자동 재발행하지 않는다.
- 운영자는 실패 종결 뒤 새 사유와 새 redrive ID로 재요청한다.
- 실행 동시성 상한은 payment-service 인스턴스별 기본 5개다.
- ACK 이후 `NOT_APPLIED`, `UNKNOWN`, `INCONSISTENT`는 즉시 실패시키지 않고 60초 deadline까지 회복을 기다린다.
- deadline의 마지막 inspection에서 양쪽이 모두 APPLIED면 `RESOLVED`, 아니면 `FAILED/CONVERGENCE`다.

## 범위

### 포함

- `FAILED/PUBLISH`, `FAILED/CONVERGENCE` 조건부 전이
- preflight UNKNOWN과 Kafka timeout/예외의 즉시 PUBLISH 실패
- ACK 저장 전 crash를 포함한 stale unpublished 작업 종결
- ACK 이후 60초 deadline과 마지막 downstream snapshot
- FAILED 이후 새 redrive ID 생성과 모든 시도 이력 보존
- queue 없는 redrive 전용 executor와 기본 동시 실행 5개 제한
- 다중 인스턴스 scan에서 기존 CAS 단일 발행 보장
- redrive 전용 downstream HTTP connect/read timeout과 기존 CircuitBreaker
- 구조화 lifecycle 로그와 저카디널리티 메트릭
- crash-window 중복 이벤트 멱등 수렴 회귀 테스트
- 성공·실패 운영 runbook

### 제외

- 클러스터 전체 합산 동시 실행 5개 보장
- DB lease/slot 테이블과 worker heartbeat
- FAILED 작업의 자동 재요청 또는 자동 재발행
- DEAD 이벤트 일괄 재발행
- 관리자 UI
- 범용 교차 서비스 reconciler
- order/product 완료 ACK 이벤트

## 상태 전이

```text
REQUESTED
  └─ executor slot + tryStart CAS winner
       └─ REDRIVING / result=null
            ├─ preflight ALREADY_APPLIED → RESOLVED_ALREADY_APPLIED
            ├─ preflight NOT_ELIGIBLE → REJECTED
            ├─ preflight UNKNOWN → FAILED/PUBLISH
            ├─ Kafka timeout·exception → FAILED/PUBLISH
            ├─ 60초 동안 result=null → FAILED/PUBLISH
            └─ broker ACK 저장 → REDRIVING / result=ACK
                 ├─ 60초 안에 양쪽 APPLIED → RESOLVED
                 └─ deadline 도달
                      ├─ 마지막 inspection 양쪽 APPLIED → RESOLVED
                      └─ 그 외 → FAILED/CONVERGENCE
```

`REJECTED`와 `FAILED`는 terminal이므로 같은 source outbox에 새 REQUESTED 작업을 만들 수 있다. `RESOLVED`와 `RESOLVED_ALREADY_APPLIED` 이력이 있으면 기존 정책대로 재요청을 막는다.

## 원자 저장 연산

`CancelOutboxRedriveRepository`에 다음 연산을 추가한다.

```java
boolean failPublish(
    long redriveId,
    String lastError,
    String beforeState,
    Instant completedAt);

boolean failConvergence(
    long redriveId,
    String lastError,
    String afterState,
    Instant completedAt);

List<CancelOutboxRedrive> findExpiredUnpublished(Instant cutoff, int limit);
List<CancelOutboxRedrive> findExpiredPublished(Instant cutoff, int limit);
```

SQL phase guard는 다음과 같이 고정한다.

- `failPublish`: `status='REDRIVING' AND result IS NULL`
- `failConvergence`: `status='REDRIVING' AND result IS NOT NULL`
- stale unpublished: `started_at <= cutoff AND result IS NULL`
- expired published: `started_at <= cutoff AND result IS NOT NULL`
- normal convergence: `started_at > cutoff AND result IS NOT NULL`

모든 update는 affected row가 1일 때만 성공이다. deadline worker, normal convergence worker, stale recovery worker가 경합해도 첫 terminal CAS 하나만 상태를 확정한다. CAS 패배는 오류가 아니라 중복 delivery로 처리한다.

기존 `(status, started_at, id)` polling index를 유지한다. 실제 쿼리 plan이 이 인덱스를 사용하는지는 MySQL integration test로 검증한다. 데이터 분포상 `result` 선택도가 부족하다고 측정될 때만 후속 인덱스를 추가한다.

## 안전 오류 코드

DB `last_error`, 구조화 로그의 error code, API 응답에 사용할 수 있는 코드는 다음 bounded 집합으로 제한한다.

| 코드 | 의미 | 실패 단계 |
| --- | --- | --- |
| `PREFLIGHT_UNKNOWN` | 발행 전 downstream 상태 판단 불가 | PUBLISH |
| `KAFKA_TIMEOUT` | broker ACK timeout | PUBLISH |
| `KAFKA_SEND_FAILED` | Kafka future 예외 | PUBLISH |
| `PUBLISH_STATE_UNKNOWN` | ACK 저장 전 crash 등 발행 여부 불명 | PUBLISH |
| `CONVERGENCE_TIMEOUT` | deadline까지 한 레그 이상 NOT_APPLIED | CONVERGENCE |
| `DOWNSTREAM_UNKNOWN` | deadline의 마지막 상태 조회 불가 | CONVERGENCE |
| `INCONSISTENT_DOWNSTREAM_STATE` | deadline의 마지막 상태가 모순 | CONVERGENCE |

exception message, stack trace, raw payload, payment key, 운영자 reason은 `last_error`에 저장하지 않는다.

## 발행 worker 실패 처리

`CancelOutboxRedriveWorker.start`의 CAS-first 계약은 유지한다.

1. `tryStart` 실패자는 어떤 inspection/source/replay도 호출하지 않는다.
2. preflight snapshot을 생성한다.
3. `UNKNOWN`이면 replay 없이 `failPublish(PREFLIGHT_UNKNOWN)`을 호출한다.
4. `REDRIVE_REQUIRED`이면 원본 key/payload로 replay한다.
5. replay adapter가 timeout과 send failure를 bounded failure code로 전달한다.
6. worker는 해당 코드로 즉시 `failPublish`한다.
7. ACK가 성공하면 기존 `recordPublished`로 ACK와 before snapshot을 저장한다.

ACK 성공 뒤 `recordPublished` 전 프로세스가 종료되거나 저장이 실패하면 행은 `REDRIVING/result=null`로 남는다. worker는 같은 실행 안에서 재발행하지 않는다. stale recovery가 60초 뒤 `PUBLISH_STATE_UNKNOWN`으로 실패 종결한다.

## 수렴과 deadline 처리

normal convergence poll은 `Clock.instant().minusSeconds(observationSeconds)`보다 새로 시작된 ACK 저장 작업만 조회한다.

- ALREADY_APPLIED: `RESOLVED`
- REDRIVE_REQUIRED: 다음 poll까지 유지
- UNKNOWN/NOT_ELIGIBLE: deadline 전에는 유지

deadline poll은 cutoff 이하의 ACK 저장 작업을 조회하고 각각 마지막 inspection을 수행한다.

- ALREADY_APPLIED: deadline 직전 수렴으로 인정해 `RESOLVED`
- REDRIVE_REQUIRED: `FAILED/CONVERGENCE`, `CONVERGENCE_TIMEOUT`
- UNKNOWN: `FAILED/CONVERGENCE`, `DOWNSTREAM_UNKNOWN`
- NOT_ELIGIBLE/INCONSISTENT: `FAILED/CONVERGENCE`, inspection reason code

마지막 inspection JSON은 항상 `after_state`에 저장한다. inspection 자체가 예상 밖 예외를 던져 snapshot을 만들 수 없으면 안전한 UNKNOWN snapshot을 생성해 `DOWNSTREAM_UNKNOWN`으로 실패 처리한다.

stale unpublished poll은 cutoff 이하의 `result=null` 작업을 inspection이나 replay 없이 `FAILED/PUBLISH`, `PUBLISH_STATE_UNKNOWN`으로 종결한다. preflight UNKNOWN과 Kafka 실패는 worker가 즉시 종결하므로 이 poll은 crash/restart 안전망이다.

## 전용 executor와 scheduling

`cancelRedriveExecutor`는 queue 없는 `ThreadPoolTaskExecutor`다.

```text
corePoolSize = maxPoolSize = cancel.redrive.max-concurrency (default 5)
queueCapacity = 0
rejectedExecutionHandler = AbortPolicy
threadNamePrefix = cancel-redrive-
waitForTasksToCompleteOnShutdown = true
awaitTerminationSeconds = cancel.redrive.shutdown-await-seconds (default 10)
```

동시성, timeout, shutdown 대기 설정값은 양수여야 하며 시작 시 fail-fast 검증한다. shutdown 대기 시간이 지나면 애플리케이션 종료를 계속하며, 미완료 DB 행은 기존 상태에 따라 stale/deadline recovery 대상이 된다.

dispatcher, normal convergence poller, deadline/stale recovery poller는 scheduler thread에서 DB 조회와 executor 제출만 수행한다. downstream HTTP와 Kafka ACK 대기는 executor thread에서만 발생한다.

executor가 가득 차서 task가 거부되면 DB 상태를 변경하지 않는다.

- REQUESTED는 다음 dispatcher poll에 다시 조회된다.
- REDRIVING convergence/deadline 작업은 다음 2초 poll에 다시 조회된다.
- 거부 횟수는 counter와 안전한 구조화 로그로 남긴다.

실제 동시 실행 상한은 executor에서 수행 중인 task 수다. 5개 task를 latch로 막은 동안 여섯 번째 REQUESTED 작업은 CAS가 실행되지 않아 REQUESTED로 남는다. 한 task가 끝나면 다음 poll에서 처리된다.

다중 payment-service 인스턴스는 각각 최대 5개를 실행할 수 있다. 같은 redrive ID가 여러 인스턴스 executor에 제출되더라도 기존 `REQUESTED -> REDRIVING` CAS 승자 하나만 preflight/replay를 실행한다.

## downstream timeout과 CircuitBreaker

order/product 취소 상태 검사 전용 qualified `RestTemplate`을 추가한다. 기존 payment 생성·취소 HTTP client의 공용 timeout 정책은 변경하지 않는다.

설정:

```yaml
cancel:
  redrive:
    inspection:
      connect-timeout-ms: 1000
      read-timeout-ms: 1000
```

두 값은 양수로 검증한다. 기존 `orderCancelStatusCircuitBreaker`, `stockRestoreStatusCircuitBreaker`는 유지한다. timeout, 5xx, CircuitBreaker open은 기존 adapter에서 UNKNOWN leg snapshot으로 변환된다.

## 구조화 로그

SLF4J 2 fluent key-value logging을 사용한다. 문자열 메시지 파싱에 의존하지 않는다.

필수 필드:

- `event`
- `redriveId`
- `sourceOutboxId`
- `status`

선택 필드:

- `failureStage`
- `errorCode`
- `topic`
- `partition`
- `offset`

이벤트:

- `cancel_redrive_requested`
- `cancel_redrive_claimed`
- `cancel_redrive_publish_acked`
- `cancel_redrive_resolved`
- `cancel_redrive_rejected`
- `cancel_redrive_failed`
- `cancel_redrive_executor_rejected`

reason, raw payload, payment key, exception message는 로그 필드에 포함하지 않는다. exception class name은 안전한 진단 필드로 사용할 수 있지만 throwable 전체는 lifecycle INFO/WARN 로그에 전달하지 않는다.

## 메트릭

최소 메트릭은 다음과 같다.

- `payment.cancel.redrive.terminal.total`
  - tags: `status`, `failure_stage`
- `payment.cancel.redrive.executor.active`
  - executor active task gauge
- `payment.cancel.redrive.executor.rejected.total`
  - tags 없음

허용 tag 값은 enum과 `none`뿐이다. redrive ID, source outbox ID, error code, operator reason, payload, payment key는 tag에 포함하지 않는다.

상태 전이와 메트릭 기록의 원자성을 결합하지 않는다. DB CAS 성공자만 counter를 증가시켜 중복 task가 terminal metric을 중복 기록하지 않게 한다.

## 재요청과 감사 이력

기존 `createRequested` 정책을 유지한다.

- REQUESTED/REDRIVING 이력이 있으면 409
- RESOLVED/RESOLVED_ALREADY_APPLIED 이력이 있으면 409
- FAILED 또는 REJECTED만 있으면 새 ID 생성 허용

새 작업은 이전 `before_state`, `after_state`, `result`를 재사용하지 않는다. source outbox와 downstream을 다시 검사한다. 이전 작업과 새 작업은 모두 조회 가능한 이력으로 보존한다.

## 테스트 전략

모든 구현은 red-green-refactor 순서로 진행한다.

### Repository MySQL integration

- PUBLISH/CONVERGENCE phase guard
- cutoff 직전, 정확히 cutoff, cutoff 이후 경계
- stale unpublished/expired published 분리
- terminal CAS 경합
- FAILED 이후 새 ID와 이전 이력 보존
- polling query EXPLAIN index 검증

### Worker unit

- preflight UNKNOWN 즉시 FAILED/PUBLISH, replay 0회
- Kafka timeout과 send failure의 bounded code
- ACK 저장 실패 시 재발행/terminal write 없음
- CAS loser의 inspection/replay/metric/log 0회

### Deadline and recovery

- 59.999초에는 terminal 전이 없음
- 60초 마지막 APPLIED는 RESOLVED
- NOT_APPLIED/UNKNOWN/INCONSISTENT의 마지막 snapshot과 FAILED/CONVERGENCE
- stale result-null은 inspection/replay 없이 FAILED/PUBLISH

### Executor

- latch로 5개 실제 task 점유
- 여섯 번째 submission reject와 REQUESTED 유지
- slot 반환 후 다음 poll에서 실행
- 설정값 0/음수 fail-fast
- convergence와 recovery도 공용 scheduler thread를 block하지 않음

### Multi-instance and crash-window integration

- 두 dispatcher/worker가 같은 ID를 scan해 replay 한 번
- Kafka ACK 후 `recordPublished` 실패 주입
- stale 기존 작업 FAILED/PUBLISH
- 새 ID 재요청과 원본 event 재발행
- 동일 cancelRequestId 중복 소비 후 order/product 상태·수량 한 번만 변경
- 새 작업 최종 RESOLVED

MySQL/Kafka 컨테이너와 Spring context는 integration test class별 한 번만 생성한다. 메서드별 container/context 재시작을 금지한다.

### Logs and metrics

- 각 lifecycle event의 필수 key-value field
- reason/payload/payment key 미노출
- terminal CAS winner만 counter 증가
- tag key/value bounded 집합
- executor active/rejected 계측

## 운영 runbook

기존 `docs/operations/cancel-outbox-redrive.md`를 확장한다.

### FAILED/PUBLISH

- `PREFLIGHT_UNKNOWN`: downstream 상태 API와 CircuitBreaker 확인 후 재요청 판단
- `KAFKA_TIMEOUT`, `KAFKA_SEND_FAILED`: broker 상태 확인 후 재요청
- `PUBLISH_STATE_UNKNOWN`: ACK 저장 전 crash 가능성을 전제로 downstream 상태를 먼저 검사한 뒤 새 작업 요청

### FAILED/CONVERGENCE

- `after_state`의 order/stock 마지막 상태 확인
- UNKNOWN이면 downstream 가용성 복구 후 새 inspection
- INCONSISTENT이면 수동 데이터 조사, 자동/수동 Kafka 발행 금지
- NOT_APPLIED timeout이면 consumer/DLQ 상태 조사

### 기타 terminal

- `RESOLVED_ALREADY_APPLIED`: 조치 없음
- `REJECTED/INVALID_PAYLOAD`: 원본 payload 수정이나 수동 발행 금지, 데이터 소유 팀 조사

모든 절차에서 payload, payment key, operator reason을 로그·메트릭·티켓에 복사하지 않는다. redrive ID, source outbox ID, status, failure stage, bounded error code만 운영 검색 키로 사용한다.

## 완료 조건

- Kafka ACK 실패와 preflight UNKNOWN이 수렴 polling 없이 FAILED/PUBLISH로 종결된다.
- ACK 저장 전 crash 상태가 60초 뒤 FAILED/PUBLISH로 종결되어 새 작업 요청이 가능하다.
- ACK 이후 60초 deadline에서 마지막 leg snapshot과 FAILED/CONVERGENCE가 저장된다.
- 실패 뒤 새 ID 요청은 최신 inspection을 수행하고 이전 이력을 보존한다.
- 인스턴스 하나에서 실제 redrive task는 최대 5개다.
- 다중 인스턴스가 같은 ID를 scan해도 replay는 한 번이다.
- crash-window 중복 event가 downstream을 두 번 변경하지 않고 새 작업이 수렴한다.
- lifecycle 로그와 최소 메트릭이 저카디널리티·비밀정보 비노출 계약을 지킨다.
- runbook만으로 각 terminal 상태의 재요청 가능 여부와 수동 조사 절차를 판단할 수 있다.
