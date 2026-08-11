# Cancel Outbox Redrive 작업 생성·조회 설계

## 목표

운영자가 `DEAD` 상태의 취소 이벤트에 대해 사유와 신원을 남겨 durable redrive 작업을 요청하고, 중복 작업 없이 `REQUESTED` 상태와 감사 정보를 조회할 수 있게 한다. 이 범위에서는 작업을 실행하거나 downstream 상태를 검사하거나 Kafka 이벤트를 재발행하지 않는다.

## 범위

### 포함

- payment DB의 `cancel_outbox_redrive` 테이블과 제약
- redrive 상태와 실패 단계 도메인 모델
- 동일 원본의 활성 작업 중복 방지
- `POST /internal/cancel-outbox/{outboxId}/redrives`
- `GET /internal/cancel-outbox/redrives/{redriveId}`
- 후속 워커가 획득할 durable `REQUESTED` 작업
- 상태 전이, repository round-trip·동시성, service 경계, controller 계약 테스트

### 제외

- `REQUESTED` 작업을 획득하는 dispatcher 또는 worker
- downstream 주문·재고 검사
- Kafka replay port와 이벤트 재발행
- 수렴 판정과 `RESOLVED`, `REJECTED`, `FAILED` 종결 처리

## 확정 규칙

- 요청 대상 원본 `cancel_event_outbox`는 존재하며 `DEAD`여야 한다.
- 원본 outbox는 작업 생성 전후 모두 `DEAD`를 유지한다.
- 사유는 필수이며 `trim()` 결과가 비어 있으면 거부한다.
- 사유 길이는 `trim()` 결과가 아니라 입력 원문의 Unicode 코드 포인트 수를 기준으로 최대 500자로 제한한다.
- 유효한 사유는 앞뒤 공백을 포함한 입력 원문 그대로 감사 이력에 저장한다.
- 요청자 ID는 기존 `X-User-Id` 문자열 원문을 저장한다.
- 활성 상태는 `REQUESTED`, `REDRIVING`이다.
- 기존 작업이 `RESOLVED` 또는 `RESOLVED_ALREADY_APPLIED`이면 새 요청을 허용하지 않는다.
- 기존 작업이 `FAILED` 또는 `REJECTED`이면 새 요청을 허용한다.
- 생성 성공 응답은 500ms 이내 `202 Accepted`와 `REQUESTED` 작업 정보를 반환한다.
- POST 경로는 downstream 검사와 replay를 호출하지 않는다.

## 접근 방식

동일 원본에 대한 요청은 원본 outbox 행의 비관적 잠금과 DB unique index를 함께 사용해 직렬화한다.

1. 생성 트랜잭션에서 `cancel_event_outbox`를 `SELECT ... FOR UPDATE`로 읽는다.
2. 원본이 없거나 `DEAD`가 아니면 작업을 만들지 않고 안정적인 오류를 반환한다.
3. 동일 원본의 기존 작업을 조회해 활성 작업과 해결 완료 작업을 구분한다.
4. 충돌이 없으면 `REQUESTED` 작업을 삽입한다.
5. 활성 상태에서만 `source_outbox_id` 값을 갖는 generated column에 unique index를 두어 애플리케이션 잠금 실수도 DB가 차단한다.

애플리케이션의 사전 조회만 사용하는 방식은 동시 요청 race를 막지 못한다. unique index만 사용하는 방식은 제약 위반만으로 활성 충돌과 해결 완료 충돌을 명확히 구분하기 어려워 채택하지 않는다.

## 데이터 모델

V21 마이그레이션으로 `cancel_outbox_redrive`를 생성한다.

| 열 | 의미 |
| --- | --- |
| `id` | bigint 자동 증가 작업 ID |
| `source_outbox_id` | 원본 `cancel_event_outbox.id` |
| `status` | redrive 진행 상태 |
| `failure_stage` | `PUBLISH`, `CONVERGENCE` 또는 null |
| `requested_by` | 운영자 식별자 |
| `reason` | 입력 원문 사유, 최대 500자 |
| `requested_at` | 요청 시각 |
| `started_at` | 실행 시작 시각 또는 null |
| `completed_at` | 종결 시각 또는 null |
| `result` | 재발행 결과 JSON 또는 null |
| `last_error` | 안전한 오류 메시지 또는 null |
| `before_state` | 실행 전 상태 JSON 또는 null |
| `after_state` | 실행 후 상태 JSON 또는 null |
| `active_source_outbox_id` | 활성 상태에서만 원본 ID를 투영하는 generated column |

`source_outbox_id`에는 조회용 index와 외래 키를 둔다. `active_source_outbox_id`에는 unique index를 두며, MySQL의 여러 null 허용 특성을 이용해 비활성 이력은 제한 없이 보존한다. 상태와 실패 단계는 문자열 열로 저장하고 허용 값은 도메인 enum과 DB check constraint 양쪽에서 고정한다.

## 컴포넌트 경계

### 도메인

`CancelOutboxRedrive`는 작업 ID, 원본 ID, 상태, 실패 단계, 감사 정보, 시각, 결과와 전후 스냅샷을 표현한다. 상태 enum은 `REQUESTED`, `REDRIVING`, `RESOLVED`, `RESOLVED_ALREADY_APPLIED`, `REJECTED`, `FAILED`를 포함한다. 실패 단계 enum은 `PUBLISH`, `CONVERGENCE`를 포함한다.

이번 범위에서도 후속 worker가 사용할 상태 전이 규칙을 도메인 테스트로 고정한다.

- `REQUESTED → REDRIVING`
- `REDRIVING → RESOLVED | RESOLVED_ALREADY_APPLIED | REJECTED | FAILED`
- 종결 상태에서는 추가 전이를 허용하지 않는다.
- `FAILED`일 때만 실패 단계가 필수이며 그 외 상태에서는 실패 단계가 null이어야 한다.

### 애플리케이션

생성 use case는 `outboxId`, `requestedBy`, `reason`을 받아 새 작업 정보를 반환한다. 조회 use case는 `redriveId`로 전체 감사 정보를 반환한다. repository port는 생성용 잠금·충돌 판정과 ID 조회를 제공하며 infrastructure 세부사항을 노출하지 않는다.

생성 서비스는 사유 검증 후 단일 트랜잭션 repository 연산만 호출한다. 기존 `CancelOutboxInspectionUseCase`, order/product 상태 port, replay port에는 의존하지 않는다.

### 인프라

`NamedParameterJdbcTemplate` 기반 adapter가 트랜잭션 안에서 원본 잠금, 기존 상태 판정, insert를 수행한다. 같은 원본에 대한 두 트랜잭션은 원본 행 잠금에서 순서대로 실행되므로 첫 요청만 생성되고 두 번째 요청은 생성된 `REQUESTED` 작업을 보고 `ACTIVE_REDRIVE_EXISTS`로 끝난다. 생성 ID는 JDBC generated key로 받고, JSON 열은 문자열로 읽고 써서 현재 payment-service의 persistence 경계와 호환한다.

### 프레젠테이션

기존 `InternalOperatorAccess`를 재사용해 `ADMIN` 역할과 비어 있지 않은 `X-User-Id`를 요구한다.

- POST body: `{ "reason": "..." }`
- POST success: HTTP 202, `redriveId`, `sourceOutboxId`, `status`, `requestedBy`, `reason`, `requestedAt`
- GET success: HTTP 200, 위 필드와 `failureStage`, `startedAt`, `completedAt`, `result`, `beforeState`, `afterState`, `lastError`

null인 선택 필드는 JSON null로 일관되게 반환한다. 원본 outbox payload와 payment key는 응답에 포함하지 않는다.

## 오류 처리

| 상황 | HTTP | 코드 |
| --- | --- | --- |
| null·공백·500자 초과 사유 | 400 | `INVALID_REQUEST` |
| 원본 outbox 미존재 | 404 | `CANCEL_OUTBOX_NOT_FOUND` |
| redrive 작업 미존재 | 404 | `CANCEL_OUTBOX_REDRIVE_NOT_FOUND` |
| 원본 outbox가 `DEAD`가 아님 | 409 | `CANCEL_OUTBOX_NOT_DEAD` |
| 활성 작업 존재 | 409 | `ACTIVE_REDRIVE_EXISTS` |
| 해결 완료 작업 존재 | 409 | `REDRIVE_ALREADY_RESOLVED` |
| 내부 인증 없음 | 401 | `INTERNAL_AUTHENTICATION_REQUIRED` |
| 관리자 역할 또는 요청자 ID 없음 | 403 | `CANCEL_OUTBOX_REDRIVE_FORBIDDEN` |

DB unique 제약 위반은 마지막 방어선으로 `ACTIVE_REDRIVE_EXISTS`에 매핑한다. 오류 응답은 stack trace, payload 원문, payment key를 노출하지 않는다.

## 요청 흐름

### 작업 생성

1. controller가 관리자와 운영자 ID를 검증한다.
2. request DTO가 사유의 null, 공백, 길이를 검증한다.
3. application service가 생성 트랜잭션을 시작한다.
4. repository가 원본 outbox 행을 잠그고 존재 여부와 `DEAD` 상태를 검증한다.
5. repository가 동일 원본의 활성 또는 해결 완료 이력을 검사한다.
6. repository가 `REQUESTED` 작업을 저장하고 생성된 ID와 시각을 반환한다.
7. controller가 HTTP 202로 응답한다.

### 작업 조회

1. controller가 관리자와 운영자 ID를 검증한다.
2. query use case가 ID로 작업을 조회한다.
3. 작업이 없으면 안정적인 404를 반환한다.
4. 작업이 있으면 상태와 전체 감사 정보를 HTTP 200으로 반환한다.

## 테스트 전략

모든 동작은 Red → Green → Refactor 순서로 구현한다.

1. 도메인 테스트로 허용 전이, 종결 상태 전이 거부, 실패 단계 불변식을 고정한다.
2. service 테스트로 사유 검증, 원문 보존, repository 위임, downstream·replay 비호출 경계를 고정한다.
3. controller 테스트로 인증, 202/200 응답, validation, 404/409 오류 계약을 고정한다.
4. MySQL integration test로 V21 마이그레이션, 전체 필드 round-trip, 원본 `DEAD` 불변성을 검증한다.
5. 두 스레드가 같은 원본으로 생성을 시도하는 integration test에서 성공 1건, `ACTIVE_REDRIVE_EXISTS` 1건, 활성 행 1개를 검증한다.
6. `RESOLVED`와 `RESOLVED_ALREADY_APPLIED` 이력 뒤의 생성 거부, `FAILED`와 `REJECTED` 이력 뒤의 재생성 허용을 검증한다.
7. payment-service 전체 테스트로 기존 취소와 outbox 기능의 회귀가 없는지 확인한다.

500ms 기준은 controller 또는 integration test에서 넉넉한 환경 변동을 고려해 요청 경로에 외부 호출이 없음을 구조적으로 검증하고, 로컬 MySQL 환경에서 실제 경과 시간도 확인한다.

## 완료 조건

- 이슈 #106의 모든 Acceptance Criteria가 자동화 테스트로 증명된다.
- 생성 직후 작업은 durable `REQUESTED`이며 원본 outbox는 `DEAD`다.
- 동시 요청에서도 활성 작업은 정확히 한 행이다.
- POST 응답 전 downstream 검사와 replay 호출은 0회다.
- 조회 API가 상태와 감사 필드를 누락 없이 반환한다.
- 기존 payment-service 테스트가 통과한다.
