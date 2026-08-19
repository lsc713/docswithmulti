# Cancel Outbox Redrive — 개발 이슈 분해

- 기준 PRD: `docs/features/cancel-outbox-redrive/prd.md`
- 분해 원칙: 운영자가 각 이슈 완료 시 API로 확인 가능한 수직 동작을 가져야 한다.
- 개발 방식: 각 이슈 안에서 Red → Green → Refactor를 완료한다.
- 예상 크기: 이슈당 반나절~1일
- GitHub 등록 상태: 미등록 — 사용자 이슈 목록 승인 후 등록

## 의존성 순서

```text
Issue 1 상태 검사
  → Issue 2 작업 생성·안전 종결
    → Issue 3 Kafka 재발행·수렴
      → Issue 4 실패·경합·운영 하드닝
```

## Issue 1 — 운영자가 DEAD 취소 이벤트의 결제·주문·재고 상태를 검사한다

### 사용자 가치

운영자는 여러 DB를 직접 조회하지 않고 payment-service 내부 API 한 번으로 특정 DEAD 이벤트가 재발행 대상인지 판단할 수 있다.

### 수직 범위

- order-service read-only `:inspect` 내부 API
- product-service read-only `:inspect` 내부 API
- payment-service의 두 downstream 상태 포트와 HTTP adapter
- payment-service DEAD 원본/payload 검사 및 판정 use case
- `GET /internal/cancel-outbox/{outboxId}`
- 내부 호출 timeout·CircuitBreaker와 `UNKNOWN` fail-closed 판정

### 구현 메모

- order-service는 `processed_cancel_event`와 요청된 order item 상태 및 주문 집계 상태를 함께 확인한다.
- product-service는 `processed_cancel_event`와 요청된 `(paymentKey, skuId, quantity)` 예약 상태를 함께 확인한다.
- payment-service는 원본 outbox payload를 파싱해 레그별 요청 DTO로 변환한다.
- downstream API는 복합 read-only 조회이므로 `POST /internal/cancel-restores/{cancelRequestId}:inspect`를 사용한다.
- downstream 응답은 `APPLIED | NOT_APPLIED | INCONSISTENT | UNKNOWN`을 공통 의미로 사용한다.
- 조회 API와 downstream API 모두 상태를 변경하지 않는다.

### 예상 변경 위치

- payment-service: presentation controller/DTO, inspection use case, source/status ports, outbox 조회 adapter, order/product HTTP adapters
- order-service: inspection use case, controller/DTO, repository query 확장
- product-service: inspection use case, controller/DTO, repository query 확장
- 각 서비스의 controller, application service, HTTP contract 및 repository integration tests

### Acceptance Criteria

- [ ] Given `DEAD` outbox, `COMPLETED` cancel request, `CANCELLED` payment, 주문상품 `ACTIVE`, 재고 예약 `RESERVED`가 존재할 때, When 운영자가 `GET /internal/cancel-outbox/{id}`를 호출하면, Then HTTP 200 응답은 `decision=REDRIVE_REQUIRED`, `order.status=NOT_APPLIED`, `stock.status=NOT_APPLIED`를 반환한다.
- [ ] Given 주문 레그만 처리 기록과 `CANCELLED` 도메인 상태를 가지고 재고 예약은 `RESERVED`일 때, When 검사하면, Then `decision=REDRIVE_REQUIRED`, `order.status=APPLIED`, `stock.status=NOT_APPLIED`를 반환한다.
- [ ] Given 주문·재고 모두 해당 `cancelRequestId` 처리 기록이 있고 payload 대상 상태가 각각 `CANCELLED`와 `RELEASED`일 때, When 검사하면, Then `decision=ALREADY_APPLIED`와 두 레그 `APPLIED`를 반환한다.
- [ ] Given 처리 기록은 있지만 payload 대상 도메인 상태가 기대값과 다를 때, When 해당 레그의 `:inspect`를 호출하면, Then `status=INCONSISTENT`와 모순된 항목 ID 및 현재 상태를 evidence에 반환하고 데이터를 변경하지 않는다.
- [ ] Given order 또는 product 상태 API가 timeout·5xx·CircuitBreaker open인 경우, When payment 검사 API를 호출하면, Then 해당 레그는 `UNKNOWN`, 전체 판정은 `UNKNOWN`이며 `ALREADY_APPLIED`로 반환하지 않는다.
- [ ] Given outbox가 존재하지 않을 때, When 검사하면, Then HTTP 404와 안정적인 오류 코드 `CANCEL_OUTBOX_NOT_FOUND`를 반환한다.
- [ ] Given outbox 상태가 `DEAD`가 아닐 때, When 검사하면, Then HTTP 200과 `decision=NOT_ELIGIBLE`, `reasonCode=OUTBOX_NOT_DEAD`를 반환하고 Kafka 발행 및 DB 쓰기를 수행하지 않는다.
- [ ] Given cancel request가 `COMPLETED`가 아니거나 payment가 `CANCELLED|PARTIAL_CANCELLED`가 아닐 때, When 검사하면, Then HTTP 200과 `decision=NOT_ELIGIBLE`, 각각 `reasonCode=CANCEL_NOT_COMPLETED|PAYMENT_NOT_CANCELLED`를 반환하고 downstream을 호출하지 않는다.
- [ ] Given payload가 파싱되지 않거나 필수 `orderItemId`, `skuId`, 양수 `quantity`가 없을 때, When 검사하면, Then `decision=NOT_ELIGIBLE`, `reasonCode=INVALID_PAYLOAD`를 반환하고 downstream 상태 API를 호출하지 않는다.
- [ ] Given 내부 인증 정보가 없을 때, When payment 검사 API를 호출하면, Then HTTP 401을 반환하고 downstream을 호출하지 않는다.
- [ ] Given 내부 인증 정보는 유효하지만 운영자 식별자가 없거나 redrive 권한이 없을 때, When payment 검사 API를 호출하면, Then HTTP 403을 반환하고 downstream을 호출하지 않는다.

### 테스트 전략

- order/product inspection service 단위 테스트
- order/product controller 계약 테스트
- order/product repository integration test
- payment inspection service 포트 mock 테스트
- payment HTTP adapter timeout/5xx/계약 테스트
- payment inspection controller integration test

---

## Issue 2 — 운영자가 redrive 작업을 생성하고 진행 상태를 추적한다

### 사용자 가치

운영자는 사유와 신원을 남겨 redrive 작업을 요청하고, 중복 요청 없이 생성된 작업의 `REQUESTED` 상태와 감사 정보를 조회할 수 있다.

### 의존성

- Issue 1의 검사 use case와 downstream 상태 포트

### 수직 범위

- payment DB `cancel_outbox_redrive` Flyway 마이그레이션(V21 예정)
- redrive 도메인 상태와 실패 단계
- 작업 repository와 활성 작업 중복 방지
- `POST /internal/cancel-outbox/{outboxId}/redrives`
- `GET /internal/cancel-outbox/redrives/{redriveId}`
- 후속 실행 워커가 획득할 durable `REQUESTED` 작업 큐

### 구현 메모

- 원본 `cancel_event_outbox`는 모든 경로에서 변경하지 않는다.
- 활성 상태는 `REQUESTED`, `REDRIVING`이다.
- DB 제약과 트랜잭션으로 동시 POST에서도 원본별 활성 작업을 하나만 허용한다.
- 이 이슈는 작업 실행기를 포함하지 않는다. 생성된 작업은 durable `REQUESTED`로 유지되며 Issue 3의 워커가 처리한다.
- POST 요청 경로는 downstream 검사나 Kafka 발행을 동기 실행하지 않는다.

### 예상 변경 위치

- payment-service Flyway `V21__create_cancel_outbox_redrive.sql`
- payment-service redrive domain/entity/repository/use case/controller/DTO
- repository concurrency integration tests, controller tests, service tests

### Acceptance Criteria

- [ ] Given 유효한 `DEAD` outbox와 공백이 아닌 500자 이하 사유 및 운영자 식별자가 있을 때, When POST를 호출하면, Then 500ms 이내 HTTP 202와 고유 `redriveId`, 초기 `status=REQUESTED`를 반환하고 원본 outbox는 `DEAD`로 유지된다.
- [ ] Given 사유가 null·공백·500자 초과일 때, When POST를 호출하면, Then HTTP 400을 반환하고 redrive 행을 생성하지 않는다.
- [ ] Given 동일 source outbox에 `REQUESTED` 또는 `REDRIVING` 작업이 있을 때, When 다시 POST하면, Then HTTP 409 `ACTIVE_REDRIVE_EXISTS`를 반환하고 활성 작업 수는 정확히 1개다.
- [ ] Given 동일 source outbox에 `RESOLVED` 또는 `RESOLVED_ALREADY_APPLIED` 작업이 있을 때, When 다시 POST하면, Then HTTP 409 `REDRIVE_ALREADY_RESOLVED`를 반환한다.
- [ ] Given 두 요청이 동일 source outbox에 동시에 POST될 때, When 두 트랜잭션이 경합하면, Then 정확히 하나만 202이고 다른 하나는 409이며 DB 활성 작업은 1행이다.
- [ ] Given 존재하는 redrive ID일 때, When 상태 조회 API를 호출하면, Then 상태, 실패 단계, 요청자, 사유, 시작·완료 시각, 전후 상태를 반환한다.
- [ ] Given 존재하지 않는 redrive ID일 때, When 상태 조회 API를 호출하면, Then HTTP 404 `CANCEL_OUTBOX_REDRIVE_NOT_FOUND`를 반환한다.
- [ ] Given POST로 작업이 생성됐을 때, When 응답이 반환된 직후 repository를 조회하면, Then 상태는 `REQUESTED`이고 downstream inspection 및 `CancelEventReplayPort` 호출 횟수는 모두 0이다.

### 테스트 전략

- 상태 전이 domain test
- Testcontainers MySQL migration/repository round-trip test
- 동시 POST concurrency integration test
- controller validation/response contract test
- POST 비동기 경계 테스트(downstream/발행 port never invoked 검증)

---

## Issue 3 — redrive 작업을 안전하게 판정·재발행하고 최종 수렴시킨다

### 사용자 가치

운영자는 요청된 작업이 실행 직전 상태에 따라 무발행 종결되거나 실제 재발행되고, 주문과 재고가 모두 처리된 시점에 최종 결과가 기록된 것을 조회할 수 있다.

### 의존성

- Issue 1의 수렴 검사
- Issue 2의 작업 저장, API와 durable `REQUESTED` 작업

### 수직 범위

- 비동기 dispatcher/worker와 CAS `REQUESTED → REDRIVING`
- 실행 직전 재검사와 `RESOLVED_ALREADY_APPLIED`, `REJECTED` 무발행 종결
- `CancelEventReplayPort` 및 Kafka adapter
- 원본 `cancelRequestId` key와 payload의 broker ACK 확인
- 2초 간격, 최대 60초 조건 기반 수렴 확인
- 부분 수렴 대기
- 최종 `RESOLVED` 상태와 전후 스냅샷
- 운영자 CLI 예제: inspect → request → status polling

### 구현 메모

- 발행 adapter는 `KafkaTemplate.send(...).get(boundedTimeout)` 또는 동등한 broker ACK 확인을 사용한다.
- redrive는 원본 payload와 원본 `cancelRequestId` key를 그대로 사용한다.
- worker는 CAS `REQUESTED → REDRIVING`에 성공한 경우에만 재검사와 발행을 수행한다.
- worker thread를 고정 sleep으로 점유하지 않도록 예약 재확인 또는 제한된 전용 executor를 사용한다. 테스트에서는 clock/scheduler를 주입해 60초를 실제로 기다리지 않는다.
- 소비자 멱등 계약을 회귀 테스트로 고정한다.

### Acceptance Criteria

- [ ] Given 두 워커가 같은 `REQUESTED` 작업을 동시에 획득하려 할 때, When CAS를 실행하면, Then affected row가 1인 워커 하나만 `REDRIVING`으로 진입하고 다른 워커는 downstream 검사나 발행을 수행하지 않는다.
- [ ] Given 실행 직전 주문·재고가 모두 `APPLIED`일 때, When worker가 작업하면, Then Kafka 발행 없이 `RESOLVED_ALREADY_APPLIED`가 되고 `before_state`, `after_state`, 요청자, 사유, 완료 시각이 저장된다.
- [ ] Given outbox/payment/payload가 Issue 1의 안전 조건을 충족하지 않을 때, When worker가 작업하면, Then Kafka 발행 없이 `REJECTED`, Issue 1에 정의된 reason code와 완료 시각이 저장된다.
- [ ] Given 실행 직전 downstream 상태가 `INCONSISTENT`일 때, When worker가 작업하면, Then Kafka 발행 없이 `REJECTED`, `reasonCode=INCONSISTENT_DOWNSTREAM_STATE`가 저장된다.
- [ ] Given 주문·재고가 모두 `NOT_APPLIED`인 유효한 DEAD 이벤트일 때, When worker가 작업하면, Then 원본 `cancelRequestId`를 Kafka key로 하고 원본 payload와 바이트 동등한 `payment.cancelled` 이벤트를 정확히 한 번 발행 시도한다.
- [ ] Given Kafka broker ACK가 성공하고 다음 검사에서 주문·재고가 모두 `APPLIED`일 때, When worker가 수렴을 확인하면, Then 작업은 `RESOLVED`, `failure_stage=null`이 되고 최종 상태와 완료 시각이 저장된다.
- [ ] Given 발행 후 주문만 `APPLIED`, 재고가 `NOT_APPLIED`일 때, When 수렴 검사를 수행하면, Then 작업은 완료 처리되지 않고 다음 검사를 예약하며 이벤트를 추가 발행하지 않는다.
- [ ] Given 발행 후 재고만 `APPLIED`, 주문이 `NOT_APPLIED`일 때, When 수렴 검사를 수행하면, Then 작업은 완료 처리되지 않고 다음 검사를 예약하며 이벤트를 추가 발행하지 않는다.
- [ ] Given 이미 처리한 한 레그가 재발행 이벤트를 다시 소비할 때, When 동일 `cancelRequestId` 이벤트가 도착하면, Then 해당 레그의 도메인 상태와 수량은 추가 변경되지 않고 미처리 레그만 적용된다.
- [ ] Given 두 레그가 60초 이내 서로 다른 시점에 `APPLIED`가 될 때, When worker가 2초 간격으로 검사하면, Then 최초로 두 레그가 모두 `APPLIED`인 검사에서 한 번만 `RESOLVED`로 전환한다.
- [ ] Given 작업이 `RESOLVED`일 때, When 운영자가 상태 API를 조회하면, Then `before_state`는 미수렴 상태, `after_state`는 두 레그 `APPLIED`, `result`는 Kafka topic·partition·offset을 포함한다.
- [ ] Given 문서화된 CLI 순서대로 검사, 요청, 상태 조회를 실행할 때, When 정상 로컬 환경에서 미수렴 fixture를 사용하면, Then 최종 출력은 `RESOLVED`이고 주문은 취소, 재고 예약은 해제 상태다.

### 테스트 전략

- Kafka adapter broker ACK 단위 테스트
- worker condition-based waiting 단위 테스트
- Testcontainers Kafka + payment MySQL integration test
- order/product 기존 중복 소비 멱등 integration test 회귀
- 로컬 운영 CLI smoke test 문서

---

## Issue 4 — 발행 실패·수렴 실패·동시 실행을 안전하게 운영한다

### 사용자 가치

운영자는 재발행이 실패하거나 수렴하지 않을 때 실패 위치를 정확히 확인하고 안전하게 다시 요청할 수 있으며, 다중 워커 환경에서도 중복 실행이 통제된다는 확신을 얻는다.

### 의존성

- Issue 1~3 전체

### 수직 범위

- `FAILED / PUBLISH`, `FAILED / CONVERGENCE`
- `UNKNOWN`, `INCONSISTENT` fail-closed 처리
- 실패 후 재요청과 재검사
- 최대 동시 실행 5개 제한
- worker 재시작·중복 delivery 안전성
- 구조화 로그와 최소 운영 메트릭
- 전체 성공/실패 운영 runbook

### 구현 메모

- broker ACK 실패는 즉시 `FAILED/PUBLISH`다.
- 발행 이후 downstream 조회 장애 또는 60초 미수렴은 `FAILED/CONVERGENCE`다.
- 실행 전 `INCONSISTENT`는 `REJECTED`, 발행 후 수렴 확인 중 `INCONSISTENT`가 지속되면 `FAILED/CONVERGENCE`로 종결해 수동 조사를 요구한다.
- 실패 작업 이후 새 요청은 허용하되 반드시 최신 상태를 다시 검사한다.
- executor의 동시 실행 상한은 기본 5이며 설정으로 변경 가능하게 한다.
- send 성공 후 상태 저장 전 crash로 중복 발행될 수 있음을 runbook에 명시하고 downstream 멱등 회귀 테스트를 유지한다.

### Acceptance Criteria

- [ ] Given Kafka send future가 timeout 또는 예외로 실패할 때, When worker가 발행하면, Then 작업은 `FAILED`, `failure_stage=PUBLISH`, 안전한 `last_error`가 저장되고 수렴 폴링은 시작하지 않는다.
- [ ] Given Kafka ACK는 성공했지만 60초 동안 한 레그 이상이 `NOT_APPLIED`, `UNKNOWN` 또는 `INCONSISTENT`일 때, When deadline에 도달하면, Then 작업은 `FAILED`, `failure_stage=CONVERGENCE`이며 마지막 레그 상태가 `after_state`에 저장된다.
- [ ] Given 이전 작업이 `FAILED`일 때, When 운영자가 같은 outbox에 새 사유로 POST하면, Then 새 redrive ID로 202를 받고 이전 시도와 새 시도가 모두 감사 이력에 남는다.
- [ ] Given 발행 성공 후 작업 상태 저장 전에 프로세스가 종료되고 새 작업이 이벤트를 재발행할 때, When downstream이 동일 `cancelRequestId`를 다시 소비하면, Then 주문과 재고는 한 번만 변경되고 최종 작업은 수렴할 수 있다.
- [ ] Given 6개의 서로 다른 redrive 작업이 동시에 `REQUESTED`일 때, When worker dispatcher가 실행하면, Then 동시에 `REDRIVING`으로 실제 실행되는 작업은 최대 5개이며 나머지는 `REQUESTED`로 대기한다.
- [ ] Given 두 payment-service 인스턴스가 동일 작업을 동시에 스캔할 때, When CAS 획득을 시도하면, Then Kafka 발행을 수행하는 인스턴스는 하나뿐이다.
- [ ] Given 운영자가 작업 ID 또는 source outbox ID로 로그를 검색할 때, When 요청·획득·발행·수렴·종결이 발생하면, Then 각 단계의 구조화 로그 필드에 `redriveId`, `sourceOutboxId`, `status`가 포함되고 `reason`, payload 원문, payment key는 메트릭 label에 포함되지 않는다.
- [ ] Given 운영 runbook을 따를 때, When `PUBLISH` 실패, `CONVERGENCE` 실패, 이미 적용, 잘못된 payload 사례를 각각 확인하면, Then 운영자는 재요청 가능 여부와 수동 조사 절차를 문서만으로 결정할 수 있다.

### 테스트 전략

- Kafka 실패 및 downstream timeout fault-injection test
- configurable clock/deadline test
- executor 동시 실행 상한 test
- 멀티 인스턴스 CAS concurrency integration test
- crash-window 중복 이벤트 멱등 convergence integration test
- 구조화 로그/메트릭 cardinality test

## 이슈별 시연 결과

| 이슈 | 완료 후 운영자가 확인 가능한 동작 |
| --- | --- |
| Issue 1 | DEAD ID 하나로 결제·주문·재고 상태와 재발행 판정 조회 |
| Issue 2 | 사유를 남겨 durable `REQUESTED` 작업을 중복 없이 생성하고 감사 조회 |
| Issue 3 | 작업의 무발행 안전 종결 또는 실제 재발행 후 주문·재고 수렴 확인 |
| Issue 4 | 발행·수렴 실패 구분, 안전한 재요청, 다중 워커 중복 방지 확인 |

## GitHub 등록 시 공통 메타데이터

- Label: `feature`, `payment`, `reliability`, `cancel-outbox-redrive`
- Milestone/Project: 저장소의 현재 운영 보드 확인 후 지정
- 각 이슈 본문: 사용자 가치, 수직 범위, Acceptance Criteria, 테스트 전략, 선행 이슈 링크 포함
- 등록 순서: Issue 1 → 2 → 3 → 4
