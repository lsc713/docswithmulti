# Cancel Outbox Redrive PRD

- 상태: 이슈 목록 승인 대기
- 작성일: 2026-08-10
- 확정 요구사항: `docs/features/cancel-outbox-redrive/spec-fixed.md`
- 기반 리뷰: `docs/reviews/2026-08-10-cancel-restore-review.md` §4.1
- 개발 이슈: `docs/features/cancel-outbox-redrive/issues.md`

## 1. 개요

### 문제

payment-service가 결제 취소와 `cancel_event_outbox` 저장까지 완료했더라도 Kafka 발행이 반복 실패해 outbox가 `DEAD`가 되면, order-service와 product-service는 `payment.cancelled` 이벤트를 받지 못한다. 그 결과 결제는 `CANCELLED`지만 주문은 배송 대기, 재고 예약은 `RESERVED`인 불일치가 영구화될 수 있다.

### 목적

내부 운영자가 단일 DEAD 취소 이벤트를 검사하고, 안전 조건을 충족하는 경우 통제된 방식으로 재발행하며, 주문과 재고의 최종 수렴을 확인하고 모든 과정을 감사할 수 있게 한다.

### v1 범위

- 내부 REST API 및 문서화된 CLI 호출
- DEAD 이벤트와 결제·주문·재고 상태 검사
- 단건 redrive 작업 생성
- 비동기 재발행 및 수렴 확인
- 작업 상태 조회
- 원본 outbox 불변 보존
- 재발행 감사 이력

## 2. 사용자 스토리

### US-1 — DEAD 이벤트 검사

내부 운영자로서, 특정 outbox ID의 원본 payload와 결제·주문·재고 상태를 한 번에 조회해 재발행이 필요한지 판단하고 싶다.

### US-2 — 미수렴 이벤트 재발행

내부 운영자로서, 주문 또는 재고가 미처리된 DEAD 이벤트에 사유를 입력해 재발행하고 작업 ID를 받고 싶다.

### US-3 — 진행 결과 확인

내부 운영자로서, redrive 작업이 요청, 실행, 해결, 거부 또는 실패 중 어떤 상태인지와 실패 단계를 조회하고 싶다.

### US-4 — 이미 수렴한 이벤트 안전 종결

내부 운영자로서, 주문과 재고가 이미 수렴한 DEAD 이벤트를 Kafka에 다시 보내지 않고 `RESOLVED_ALREADY_APPLIED`로 감사 기록에 남기고 싶다.

### US-5 — 중복 실행 방지

내부 운영자로서, 여러 운영자나 워커가 동시에 같은 원본 이벤트를 처리해도 하나의 활성 작업과 하나의 실행자만 존재하기를 원한다.

## 3. 기능 요구사항

### 3.1 검사

- `GET /internal/cancel-outbox/{outboxId}`는 쓰기 없이 상태를 조회한다.
- 원본 outbox, payload 요약, 결제 상태, 주문 레그 상태, 재고 레그 상태를 반환한다.
- `REDRIVE_REQUIRED`, `ALREADY_APPLIED`, `NOT_ELIGIBLE`, `UNKNOWN` 중 하나의 판정과 근거를 반환한다.
- downstream 조회 실패는 처리 완료로 추정하지 않고 `UNKNOWN`으로 반환한다.

### 3.2 작업 요청

- `POST /internal/cancel-outbox/{outboxId}/redrives`는 필수 사유와 운영자 식별자를 검증한다.
- 요청 저장 후 500ms 이내 `202 Accepted`와 redrive ID를 반환한다.
- 동일 원본에 활성 작업이 있으면 `409 Conflict`로 거부한다.
- 이미 해결된 원본에 대한 새 요청도 `409 Conflict`로 거부한다.

### 3.3 작업 실행

- 워커는 CAS로 `REQUESTED → REDRIVING` 전이에 성공한 경우에만 작업한다.
- 워커는 실행 직전에 원본과 downstream 상태를 다시 검사한다.
- 이미 수렴했으면 발행 없이 `RESOLVED_ALREADY_APPLIED`로 종결한다.
- 안전 조건을 충족하지 않으면 `REJECTED`로 종결한다.
- 재발행이 필요하면 원본 payload를 `payment.cancelled` 토픽에 원본 `cancelRequestId` 키로 발행한다.
- Kafka ACK만으로 해결 처리하지 않고 주문과 재고의 최종 수렴을 확인한다.

### 3.4 수렴 확인

- 2초 간격, 최대 60초 동안 확인한다.
- 주문과 주문상품이 모두 취소되고, 대상 재고 예약이 모두 해제된 경우 `RESOLVED`다.
- 한 레그만 처리된 부분 수렴은 계속 대기한다.
- 60초 내 전체 수렴하지 않으면 `FAILED / CONVERGENCE`다.
- Kafka 발행 실패는 `FAILED / PUBLISH`다.

### 3.5 감사

- 원본 `cancel_event_outbox`는 수정하거나 삭제하지 않는다.
- 별도 `cancel_outbox_redrive`에 요청자, 사유, 시각, 상태 전이, 실패 단계, 오류, 전후 상태를 저장한다.
- 동일 원본의 복수 실패 시도를 모두 보존한다.

## 4. 데이터 모델

```text
cancel_outbox_redrive
  id
  source_outbox_id
  status
  failure_stage
  requested_by
  reason
  requested_at
  started_at
  completed_at
  result
  last_error
  before_state
  after_state
```

상태와 용어의 상세 정의는 `spec-fixed.md`를 따른다.

## 5. API

```http
GET  /internal/cancel-outbox/{outboxId}
POST /internal/cancel-outbox/{outboxId}/redrives
GET  /internal/cancel-outbox/redrives/{redriveId}
```

- public API Gateway에는 라우팅하지 않는다.
- 내부 서비스 인증 또는 운영자 식별 헤더가 없으면 거부한다.
- 응답에는 내부 스택 트레이스를 노출하지 않는다.

## 6. 아키텍처 대안

### 안 A — Payment 오케스트레이션 + downstream 상태 조회 API

payment-service가 redrive 작업의 단일 소유자가 된다. order-service와 product-service에 취소 처리 상태를 조회하는 read-only 내부 API를 추가하고, payment-service 워커가 두 API를 폴링해 수렴을 판단한다.

```text
Operator
  → payment internal redrive API
  → cancel_outbox_redrive
  → Kafka payment.cancelled 재발행
  → order/product consumer
  → payment worker가 order/product 상태 API 폴링
  → RESOLVED 또는 FAILED/CONVERGENCE
```

### 안 B — Downstream 완료 ACK 이벤트

order-service와 product-service가 취소 처리를 완료할 때 각각 완료 ACK 이벤트를 Kafka에 발행한다. payment-service는 ACK 두 개를 소비해 redrive 작업의 수렴을 판단한다.

```text
payment.cancelled 재발행
  ├→ order 처리 → cancel.order-applied
  └→ stock 처리 → cancel.stock-applied

payment-service가 두 ACK를 모아 RESOLVED
```

### 안 C — 별도 Operations Reconciler

신규 운영 서비스가 payment, order, product 상태 조회와 Kafka 재발행을 모두 소유한다. payment-service에는 redrive API와 작업 상태를 두지 않는다.

```text
Operator
  → operations-reconciler
  → payment/order/product 상태 조회
  → Kafka 재발행
  → 수렴 확인 및 감사
```

### 7개 기준 비교

| 기준 | 안 A: Payment + 상태 API | 안 B: 완료 ACK 이벤트 | 안 C: 별도 Reconciler |
| --- | --- | --- | --- |
| 데이터 구조 | payment DB에 redrive 이력 1개, downstream은 기존 처리 테이블 조회 | payment DB redrive + 레그별 ACK 수신 상태 또는 ACK 테이블 필요 | 신규 서비스 DB에 redrive·스냅샷 저장 |
| API 레이어 변경지점 | payment 내부 API 3개 + order/product read-only 상태 API 각 1개 | payment 내부 API 3개, order/product 완료 이벤트 발행 계약 추가 | 신규 서비스 API 3개 + 세 서비스 상태 조회 API 필요 |
| 상태관리 변경지점 | payment 워커가 작업 상태와 60초 폴링을 관리 | payment ACK 소비자가 레그별 도착 상태와 timeout을 관리 | 신규 서비스가 전체 상태기계와 실행 제한 관리 |
| 핵심 동작 | 원본 payload 재발행 후 두 downstream API를 조건 대기 | 원본 payload 재발행 후 두 ACK 이벤트가 도착할 때까지 대기 | 외부 오케스트레이터가 조회·발행·수렴을 모두 조정 |
| 컴포넌트 구조 | payment redrive controller/service/worker + order/product query controller | 안 A의 payment API/worker 일부 + order/product ACK publisher + payment ACK consumers | 신규 애플리케이션, DB, 배포, 인증, 클라이언트, 워커 |
| 기존 패턴과 일관성 | payment의 기존 RestTemplate/CB 포트 패턴과 가장 유사 | Kafka/outbox 패턴과 일치하지만 새 이벤트 계약과 발행 보장 필요 | 서비스 분리 원칙에는 맞지만 현재 규모에는 새로운 운영 경계 |
| 테스트 용이성 | 포트 mock 단위 테스트와 HTTP 계약 테스트가 단순, 60초 폴링은 가상 시간 필요 | 이벤트 순서·중복·유실·ACK outbox까지 테스트 범위가 큼 | 컴포넌트 테스트는 가능하지만 통합환경과 배포 테스트 비용이 가장 큼 |

### 비교 요약

| 안 | 장점 | 단점 | 적합 시점 |
| --- | --- | --- | --- |
| A | v1 범위가 가장 작고 현재 HTTP/CB 패턴 재사용, 운영자가 즉시 상태를 조회 가능 | payment가 downstream 상태를 동기 조회하며 일시적 결합 증가 | 수동 단건 redrive 중심의 v1 |
| B | 완전 이벤트 기반이며 반복 폴링 없음, 장기적으로 수렴 신호가 명확 | ACK 발행도 outbox가 필요하고 세 서비스 이벤트 계약이 늘어남 | 자동 복구·대량 처리 단계 |
| C | 운영 복구 책임을 별도 경계로 격리, 향후 범용 리컨실러로 확장 용이 | 신규 서비스·DB·배포가 필요해 v1 대비 과설계 | 여러 도메인 불일치를 통합 운영할 단계 |

### 추천

v1에는 **안 A**를 추천한다.

- 이미 payment-service가 order/product를 HTTP로 호출하는 포트·어댑터·CircuitBreaker 패턴을 가지고 있다.
- 사용자가 선택한 단건 수동 복구와 60초 수렴 확인에 가장 작은 변경으로 맞는다.
- 상태 조회 API는 read-only라 downstream 도메인 변경을 최소화할 수 있다.
- v1.2 자동 복구 또는 범용 리컨실러가 필요해질 때 안 B나 안 C로 진화할 수 있다.

단점은 payment-service가 두 downstream 가용성에 의존한다는 점이다. 따라서 조회 실패를 성공으로 추정하지 않고 `UNKNOWN` 또는 `FAILED / CONVERGENCE`로 처리하며, CircuitBreaker와 timeout을 적용해야 한다.

## 7. 기술 결정

### ADR-1 — Payment 오케스트레이션과 이전 가능한 포트 경계

**Context**

v1은 단건 수동 redrive를 빠르게 제공해야 한다. 현재 payment-service에는 order/product를 호출하는 RestTemplate, CircuitBreaker, application port 패턴이 이미 있다. 반면 범용 Operations Reconciler를 별도 서비스로 도입하면 신규 애플리케이션, DB, 배포, 인증, 운영 체계가 필요해 현재 범위를 크게 초과한다.

다만 redrive 오케스트레이션이 payment-service의 HTTP 클라이언트, KafkaTemplate, JPA 구현에 직접 결합되면 향후 Operations Reconciler로 책임을 이전하기 어렵다.

**Decision**

아키텍처 안 A를 선택한다. v1에서는 payment-service가 redrive API, 작업 상태기계, 이벤트 재발행, 수렴 확인과 감사 이력을 소유한다.

오케스트레이션은 다음 application port 뒤에 둔다.

```text
Inbound use cases
  CancelOutboxInspectionUseCase
  CancelOutboxRedriveUseCase
  CancelOutboxRedriveQuery

Outbound ports
  CancelOutboxSourcePort
  CancelOutboxRedriveRepository
  CancelEventReplayPort
  OrderCancelStatusPort
  StockRestoreStatusPort
  RedriveExecutionPort 또는 RedriveTaskDispatcher
```

각 포트의 책임은 다음과 같다.

| 포트 | 책임 | v1 구현 |
| --- | --- | --- |
| `CancelOutboxSourcePort` | DEAD 원본과 payload를 읽고 불변 원본을 제공 | payment DB adapter |
| `CancelOutboxRedriveRepository` | 작업 생성, 활성 중복 방지, CAS, 감사 상태 저장 | payment DB JPA/JDBC adapter |
| `CancelEventReplayPort` | 원본 key/payload를 `payment.cancelled`에 발행하고 broker ACK 확인 | Kafka adapter |
| `OrderCancelStatusPort` | 주문 레그 적용 여부를 표준 상태로 조회 | order internal HTTP adapter |
| `StockRestoreStatusPort` | 재고 레그 적용 여부를 표준 상태로 조회 | product internal HTTP adapter |
| `RedriveExecutionPort` | 요청 API와 실제 비동기 실행을 분리 | Spring task executor adapter |

오케스트레이터는 HTTP 응답 DTO나 KafkaTemplate을 알지 않고 다음 표준 상태만 사용한다.

```text
LegStatus
  APPLIED       // 처리 기록과 도메인 최종 상태가 모두 확인됨
  NOT_APPLIED   // 미처리가 명확함
  INCONSISTENT  // 처리 기록과 도메인 상태가 서로 모순됨
  UNKNOWN       // 장애·타임아웃 등으로 판단 불가
```

order-service와 product-service에는 동일한 의미를 갖는 read-only 내부 계약을 추가한다. 부분취소 대상을 정확히 판정하려면 `cancelRequestId`만으로 부족하므로 payment-service가 원본 outbox payload에서 추출한 대상 목록을 요청 본문으로 전달한다.

```http
POST /internal/cancel-restores/{cancelRequestId}:inspect
```

이 POST는 상태를 변경하지 않는 복합 조회다. order-service 요청은 `orderItemIds`, product-service 요청은 `paymentKey`와 `skuId/quantity` 목록을 받는다.

```json
// order-service request
{
  "orderItemIds": [34]
}
```

```json
// product-service request
{
  "paymentKey": "pay_8256d9da7e8941c4ad42",
  "items": [{"skuId": 1, "quantity": 1}]
}
```

서비스별 응답은 공통 필드 `leg`, `status`, `checkedAt`, `evidence`를 사용한다. `evidence`의 세부 내용은 레그별로 다를 수 있지만 payment 오케스트레이터의 판정은 `LegStatus`에만 의존한다.

```json
{
  "cancelRequestId": "29",
  "leg": "ORDER",
  "status": "APPLIED",
  "checkedAt": "2026-08-10T06:00:00Z",
  "evidence": {
    "processedEvent": true,
    "orderStatus": "CANCELLED",
    "allItemStatuses": "CANCELLED"
  }
}
```

향후 C안으로 전환할 때는 application port와 downstream HTTP 계약을 유지하고 다음 소유권만 이동할 수 있어야 한다.

```text
v1
payment-service
  └─ Redrive orchestrator + repository + API

future
operations-reconciler
  └─ 동일 port 계약의 orchestrator + repository + API
payment-service
  └─ 원본 outbox 조회 API 또는 source adapter 제공
```

payment-service와 Operations Reconciler가 동시에 같은 redrive 작업을 소유하는 이중 운영 기간은 허용하지 않는다. 이전 시점에는 단일 실행 소유자를 설정하고 기존 payment redrive API를 차단하거나 새 서비스로 명시적으로 위임한다.

**Alternatives**

- 안 B, downstream 완료 ACK 이벤트는 선택하지 않는다. 폴링은 제거할 수 있지만 order/product 양쪽에 새로운 이벤트 발행과 발행 보장용 outbox가 필요하며, v1의 단건 수동 복구보다 구현·운영 범위가 크다.
- 안 C, 별도 Operations Reconciler는 이번에 선택하지 않는다. 책임 분리와 장기 확장성은 가장 좋지만 신규 서비스, DB, 배포, 인증과 관측성을 도입해야 하므로 현재 문제에 비해 초기 비용이 크다.
- payment-service가 order/product DB를 직접 조회하는 방식은 선택하지 않는다. 구현은 빠르지만 서비스별 데이터 소유권을 침해하고 스키마 결합 때문에 C안 이전이 어려워진다.

**Consequences**

장점:

- 기존 payment HTTP/CB 패턴을 재사용해 v1 구현 범위를 줄인다.
- API, 오케스트레이션, 저장소, Kafka 발행, downstream 조회가 분리돼 단위 테스트가 쉽다.
- order/product 내부 상태 계약을 향후 Operations Reconciler가 재사용할 수 있다.
- direct DB 접근 없이 서비스 데이터 소유권을 유지한다.
- 관리자 웹 UI는 같은 inbound use case/API를 재사용할 수 있다.

단점:

- v1 동안 payment-service가 downstream 두 서비스의 가용성과 응답 지연에 의존한다.
- order/product에 read-only 내부 API와 상태 판정 로직을 추가해야 한다.
- 2초 폴링이 발생하며 대량 redrive에는 적합하지 않다.
- 인터페이스를 분리해도 실제 C안 이전에는 API 소유권, 인증, 저장소 데이터 이전을 별도로 설계해야 한다.
- Kafka 발행 성공 후 프로세스 종료 시 중복 발행 가능성이 남으므로 downstream 멱등 계약이 계속 필요하다.

### ADR-2 — 수렴 판정은 처리 기록과 도메인 상태를 함께 사용

**Context**

`processed_cancel_event` 존재만 확인하면 핸들러가 실행됐다는 사실은 알 수 있지만, 데이터 오류나 과거 수동 변경으로 실제 주문·재고 상태가 기대값과 다를 수 있다. 반대로 도메인 상태만 확인하면 어떤 cancel request가 그 상태를 만들었는지 추적하기 어렵다.

**Decision**

각 downstream 상태 API는 처리 기록과 도메인 최종 상태를 함께 검사한다.

- ORDER `APPLIED`: 해당 `cancelRequestId` 처리 기록 존재, payload 대상 주문상품 전체 `CANCELLED`, 주문 집계 상태가 기대값과 일치
- STOCK `APPLIED`: 해당 `cancelRequestId` 처리 기록 존재, payload 대상 예약 전체 `RELEASED`
- 처리 기록과 도메인 상태가 모순되면 `INCONSISTENT`
- 조회 장애나 판정에 필요한 데이터 부재는 `UNKNOWN`
- 처리 기록이 없고 대상이 아직 활성 상태면 `NOT_APPLIED`
- payment-service는 원본 outbox payload에서 order item과 stock item 대상을 추출해 각 레그의 `:inspect` 요청에 전달한다.

**Alternatives**

- `processed_cancel_event`만 확인하는 방식은 조회가 단순하지만 조용한 데이터 불일치를 해결된 것으로 오판할 수 있어 제외한다.
- 도메인 상태만 확인하는 방식은 현재 상태는 알 수 있지만 cancel request와의 인과관계 및 멱등 처리 증거가 약해 제외한다.

**Consequences**

- 해결 상태의 신뢰도가 높아지고 기존 데이터 불일치도 `INCONSISTENT`로 드러난다.
- 상태 조회 쿼리와 테스트가 단순 존재 확인보다 복잡해진다.
- 부분취소 대상 목록을 내부 HTTP 요청에 포함하므로 요청 크기가 증가하지만 v1 단건 처리 범위에서는 허용한다.

## 8. Out of Scope

- 관리자 웹 UI
- DEAD 이벤트 일괄 재발행
- 조건부 또는 무조건 자동 재발행
- 범용 교차 서비스 리컨실러
- 별도 Operations Reconciler 서비스 생성·배포
- payment-service와 Operations Reconciler의 이중 실행 또는 dual ownership
- downstream 완료 ACK 이벤트 및 ACK용 outbox 추가
- payment-service의 order/product DB 직접 접근
- redrive 전용 공용 라이브러리 또는 신규 shared module 추출
- 실제 Toss 취소 API 변경
- order/product 소비자 DLQ 재구동 로직 변경
- 원본 `cancel_event_outbox` 상태 변경 또는 삭제
- v1 데이터의 Operations Reconciler 이전 자동화

## 9. 용어 정의

`docs/features/cancel-outbox-redrive/spec-fixed.md` §13을 단일 용어 기준으로 사용한다.
