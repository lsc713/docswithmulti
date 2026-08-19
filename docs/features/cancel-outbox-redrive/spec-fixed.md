# DEAD 취소 아웃박스 통제 재발행 — 확정 요구사항

- 기능명: Cancel Outbox Redrive
- 단계: Feature Planner 1단계 — 요구사항 확정
- 작성일: 2026-08-10
- 기반 리뷰: `docs/reviews/2026-08-10-cancel-restore-review.md` §4.1

## 1. 목적

결제 취소는 완료됐지만 `payment.cancelled` 이벤트의 Kafka 발행이 최종 실패한 경우, 운영자가 현재 서비스 상태를 확인하고 안전하게 이벤트를 재발행해 주문과 재고를 최종 수렴시킬 수 있어야 한다.

이 기능은 Kafka 소비 이후의 order/product `cancel_restore_dlq` 재처리와 구분된다. 대상은 payment-service의 `cancel_event_outbox.status = 'DEAD'`인 이벤트다.

## 2. Primary User

v1의 사용자는 내부 운영자다.

- 운영자는 내부 REST API를 사용한다.
- CLI는 별도 실행 프로그램이 아니라 내부 API를 호출하는 문서화된 명령으로 제공한다.
- public API Gateway에는 노출하지 않는다.
- 관리자 웹 콘솔과 자동 복구는 후속 버전에서 제공한다.

## 3. 최소 동작 시나리오

### 시나리오 A — 미수렴 이벤트 재발행

Given 결제 취소가 완료되고 outbox가 `DEAD`이며 주문 또는 재고가 미처리된 상태에서,
When 운영자가 사유를 입력해 재발행을 요청하면,
Then 이벤트를 재발행하고 주문과 재고가 모두 처리된 것을 확인한 후 작업을 `RESOLVED`로 종결한다.

부분 수렴도 이 시나리오에 포함한다. 주문만 처리됐거나 재고만 처리된 경우 이벤트 전체를 재발행하며, 이미 처리한 소비자 레그는 `cancelRequestId` 기반 멱등성으로 중복 변경을 방지한다.

### 시나리오 B — 이미 수렴한 이벤트 종결

Given outbox는 `DEAD`지만 주문과 재고가 이미 모두 처리된 상태에서,
When 운영자가 검사 결과를 확인한 뒤 재발행 작업을 요청하면,
Then Kafka에 재발행하지 않고 작업을 `RESOLVED_ALREADY_APPLIED`로 종결한다.

### 시나리오 C — 재발행 조건 불충족

Given payload 오류, 결제 미취소 또는 대상 상태 불일치로 재발행 안전 조건을 만족하지 않을 때,
When 운영자가 재발행을 요청하면,
Then 이벤트를 발행하지 않고 작업을 `REJECTED`로 기록하며 구체적인 거부 사유를 반환한다.

## 4. 데이터 저장 방식

원본 `cancel_event_outbox` 행은 `DEAD` 상태 그대로 보존한다. 재발행 작업과 감사 이력은 별도 `cancel_outbox_redrive` 테이블에 저장한다.

최소 저장 정보는 다음과 같다.

```text
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

- 원본 장애 기록을 수정하거나 삭제하지 않는다.
- 동일 원본에 대한 복수의 재발행 시도를 각각 보존한다.
- 상태 스냅샷은 결제·주문·재고의 재발행 전후 상태를 추적할 수 있어야 한다.

## 5. 상태 모델

### 진행 상태

| 상태 | 의미 |
| --- | --- |
| `REQUESTED` | 운영자가 재발행을 요청했고 아직 워커가 시작하지 않음 |
| `REDRIVING` | 단일 워커가 작업을 획득해 발행과 수렴 확인을 수행 중 |
| `RESOLVED` | 재발행 후 주문과 재고가 모두 최종 상태로 수렴함 |
| `RESOLVED_ALREADY_APPLIED` | 재발행 전에 이미 주문과 재고가 수렴해 발행 없이 종결함 |
| `REJECTED` | 사전 조건이 충족되지 않아 이벤트를 발행하지 않음 |
| `FAILED` | 발행 또는 수렴 확인 단계에서 작업이 실패함 |

### 실패 단계

`FAILED` 상태는 `failure_stage`로 실패 위치를 구분한다.

| failure_stage | 의미 |
| --- | --- |
| `PUBLISH` | Kafka 재발행 실패 |
| `CONVERGENCE` | Kafka 발행 후 제한 시간 내 주문·재고 수렴을 확인하지 못함 |

`REJECTED`는 실행 실패가 아니라 안전 조건에 따른 실행 거부이므로 `FAILED`와 구분한다.

## 6. 동시성 및 경계 조건

- 동일 `source_outbox_id`에는 활성 작업을 하나만 허용한다.
- 활성 상태는 `REQUESTED`, `REDRIVING`이다.
- 활성 작업이 있으면 새 요청은 `409 Conflict`로 거부한다.
- 이전 작업이 `FAILED` 또는 `REJECTED`라면 새 작업으로 다시 요청할 수 있다.
- `RESOLVED` 또는 `RESOLVED_ALREADY_APPLIED`로 종결된 원본에는 새 요청을 허용하지 않는다.
- `REQUESTED → REDRIVING`은 CAS 조건부 UPDATE로 처리해 한 워커만 작업을 획득한다.
- CAS의 affected row가 0이면 다른 워커가 이미 작업을 획득한 것으로 판단하고 발행하지 않는다.
- Kafka 발행 성공 후 DB 상태 저장 전에 프로세스가 종료될 수 있으므로 소비자 멱등성은 필수다.
- 운영자 사유는 필수이며 공백을 허용하지 않고 최대 500자로 제한한다.
- v1은 운영자 수동 재요청 횟수에 상한을 두지 않으며 모든 시도를 감사 이력에 보존한다.

## 7. 오류 처리

### 사전 조건 거부

- payload가 파싱되지 않음
- outbox가 `DEAD`가 아님
- 연결된 취소 요청이 완료되지 않음
- 결제가 취소 상태가 아님
- payload의 필수 주문상품, SKU 또는 수량 정보가 유효하지 않음

위 조건에서는 Kafka로 발행하지 않고 `REJECTED`와 안전한 거부 사유를 기록한다.

### 발행 실패

- 상태를 `FAILED`로 변경한다.
- `failure_stage = PUBLISH`를 기록한다.
- 원본 outbox는 `DEAD`로 유지한다.
- 운영자는 새 작업으로 다시 요청할 수 있다.

### 수렴 확인 실패

- 상태를 `FAILED`로 변경한다.
- `failure_stage = CONVERGENCE`를 기록한다.
- 이미 이벤트가 발행됐을 가능성이 있으므로 재요청 전 현재 상태를 다시 검사한다.

### 오류 응답

- 내부 스택 트레이스를 반환하지 않는다.
- 작업 ID, 실패 단계, 운영자가 이해할 수 있는 안전한 메시지를 제공한다.
- Kafka 전송 성공만으로 `RESOLVED`로 처리하지 않는다.

## 8. 내부 API

### DEAD 이벤트 및 현재 상태 검사

```http
GET /internal/cancel-outbox/{outboxId}
```

응답에는 원본 outbox 정보, payload 요약, 결제·주문·재고 현재 상태, 재발행 필요 여부와 판단 사유가 포함돼야 한다.

이 API는 상태를 변경하지 않는다. `RESOLVED_ALREADY_APPLIED`, `REJECTED`를 포함한 작업 결과와 감사 이력은 POST로 생성된 redrive 작업이 기록한다.

### 재발행 작업 요청

```http
POST /internal/cancel-outbox/{outboxId}/redrives
Content-Type: application/json

{
  "reason": "Kafka 장애 기간 중 DEAD 이벤트 복구"
}
```

작업을 생성하고 `202 Accepted`와 redrive ID를 반환한다.

워커는 작업을 획득한 뒤 사전 조건을 다시 검사한다. 이미 수렴한 경우 발행 없이 `RESOLVED_ALREADY_APPLIED`, 안전 조건이 충족되지 않으면 `REJECTED`, 재발행이 필요하면 Kafka 발행과 수렴 확인을 수행한다.

### 재발행 작업 조회

```http
GET /internal/cancel-outbox/redrives/{redriveId}
```

진행 상태, 실패 단계, 요청자, 사유, 시작·완료 시각, 재발행 전후 상태를 반환한다.

## 9. 인증 및 감사

- API는 public API Gateway에 노출하지 않는다.
- 내부 네트워크와 내부 서비스 인증 또는 운영자 식별 헤더를 요구한다.
- 요청자 식별자가 없으면 작업을 생성하지 않는다.
- 요청자, 사유, 시각, 원본 이벤트, 상태 전이, 결과, 오류를 감사 이력으로 보존한다.
- 관리자 웹 콘솔은 후속 버전에서 동일 API를 사용한다.

## 10. 성능 및 실행 제한

- v1은 단건 재발행만 지원한다.
- 검사 API는 정상 환경에서 2초 이내 응답한다.
- 재발행 요청 API는 작업 생성 후 500ms 이내 `202 Accepted`를 반환한다.
- 작업 조회 API는 정상 환경에서 500ms 이내 응답한다.
- 수렴 상태는 2초 간격으로 최대 60초 확인한다.
- 60초 안에 주문과 재고가 모두 처리되면 `RESOLVED`로 전환한다.
- 60초가 지나면 `FAILED / CONVERGENCE`로 전환한다.
- 동시에 실행할 재발행 작업은 기본 5개로 제한한다.

## 11. 단계별 확장

### v1 — 이번 구현 범위

- 내부 API/CLI 수동 재발행
- 상태 검사
- 단건 작업 요청
- 작업 상태 조회
- 감사 이력
- 최종 수렴 확인

### v1.1 — 관리자 웹 콘솔

- DEAD 목록과 상세 조회
- 사유 입력과 재발행 요청
- 진행 상태 및 결과 표시
- v1 내부 API 재사용

### v1.2 — 조건부 자동 복구

- 명백하게 안전한 이벤트만 자동 재발행
- 재시도 횟수, 실행 시간대, 동시 실행 제한
- 불명확한 이벤트는 운영자 승인 유지

### 향후 — 교차 서비스 정합성 리컨실러

- 결제·주문·재고 상태의 주기적 대조
- 아웃박스 상태와 무관한 서비스 간 불일치 탐지

## 12. Out of Scope

v1에서는 다음을 구현하지 않는다.

- 관리자 웹 UI
- DEAD 이벤트 일괄 재발행
- 조건부 또는 무조건 자동 재발행
- 범용 교차 서비스 리컨실러
- 실제 Toss 취소 API 변경
- order/product 소비자 DLQ 재구동 로직 변경
- 원본 `cancel_event_outbox`의 `DEAD` 상태 덮어쓰기 또는 삭제

## 13. 용어 정의

| 용어 | 정의 |
| --- | --- |
| 취소 아웃박스 | payment-service가 취소 완료와 같은 트랜잭션에서 저장하는 `cancel_event_outbox` 이벤트 |
| DEAD | 취소 이벤트의 Kafka 발행이 최대 재시도 횟수를 초과해 자동 발행 대상에서 제외된 원본 상태 |
| Redrive | 원본 DEAD 이벤트를 검사한 뒤 Kafka에 다시 발행하고 최종 수렴을 확인하는 운영 복구 작업 |
| 원본 outbox | 최초 취소 트랜잭션에서 생성된 `cancel_event_outbox` 행. redrive 중에도 변경하지 않음 |
| 활성 작업 | `REQUESTED` 또는 `REDRIVING` 상태의 redrive 작업 |
| 수렴 | 결제가 취소된 상태에서 대상 주문과 주문상품이 취소되고 대상 재고 예약이 해제돼 재고가 복원된 상태 |
| 부분 수렴 | 주문과 재고 중 한쪽만 처리된 상태 |
| CAS | 현재 상태가 예상 상태일 때만 상태를 변경하는 조건부 UPDATE. 한 워커만 작업을 획득하는 데 사용 |
| RESOLVED | 재발행 후 최종 수렴이 확인된 redrive 결과 |
| RESOLVED_ALREADY_APPLIED | 재발행 전에 이미 최종 수렴이 확인돼 발행 없이 종결된 결과 |
| REJECTED | 안전 조건 불충족으로 이벤트를 발행하지 않은 결과 |
| FAILED | 발행 또는 수렴 확인 실행 중 실패한 결과 |

## 14. 성공 기준

- 운영자가 DEAD 이벤트의 결제·주문·재고 상태와 재발행 가능 여부를 조회할 수 있다.
- 미수렴 DEAD 이벤트를 단건 재발행하고 작업 ID를 받을 수 있다.
- 동일 원본의 활성 작업이 중복 생성되거나 두 워커가 동시에 발행하지 않는다.
- 이미 수렴한 이벤트는 Kafka 발행 없이 `RESOLVED_ALREADY_APPLIED`로 종결된다.
- 조건 불충족 이벤트는 발행 없이 `REJECTED`로 기록된다.
- 발행 실패와 수렴 실패를 서로 구분할 수 있다.
- `RESOLVED`는 주문과 재고의 최종 수렴을 확인한 후에만 기록된다.
- 원본 DEAD 기록과 모든 재발행 시도가 감사 가능한 형태로 보존된다.
