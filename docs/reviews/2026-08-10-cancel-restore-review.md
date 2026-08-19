# 결제 취소 및 재고 복원 흐름 리뷰

- 작성일: 2026-08-10
- 검토 범위: 구매자 취소 요청 → 관리자 승인 → 결제 취소 → 취소 이벤트 발행 → 주문 취소 → 재고 복원 → 구매자 주문목록 반영
- 검토 방식: 로컬 E2E 실행, 결제·주문·상품 DB 교차 검증, 관련 구현 및 테스트 정적 리뷰
- 결론: 신규 검증 건은 정상 처리됐지만, 기존 `DEAD` 아웃박스 2건에서 실제 데이터 불일치가 발견됐다. 운영 안정성을 위해 DEAD 재처리 체계와 승인·복원 경로의 동시성 및 불변식 보강이 필요하다.

## 1. 요약

이번에 검증한 결제 건은 취소 요청부터 재고 복원까지 정상적으로 완료됐다.

| 항목 | 결과 |
| --- | --- |
| 결제키 | `pay_8256d9da7e8941c4ad42` |
| 취소 금액 | 29,000원 |
| 관리자 결정 | `APPROVED` |
| 취소 요청 | `COMPLETED` |
| 결제/결제상품 | `CANCELLED` / `CANCELLED` |
| 주문/주문상품 | `CANCELLED` / `CANCELLED` |
| 취소 이벤트 | `PUBLISHED`, retry 0 |
| 재고 | 483 → 484 |
| 재고 예약 | `RESERVED` → `RELEASED` |
| 구매자 주문목록 | `취소됨` 표시, 재요청 버튼 없음 |

다만 기존 로컬 데이터에서 취소 이벤트가 `DEAD`로 종료된 2건을 확인했다. 두 건 모두 결제만 취소되고 주문과 재고가 복원되지 않아 서비스 간 상태가 불일치한다. 현재 구조에는 DEAD 이벤트를 다시 처리하는 자동 또는 관리형 경로가 없다.

## 2. 검증 환경과 범위

검증에는 다음 서비스와 인프라가 사용됐다.

- frontend
- api-gateway
- user-service
- payment-service
- order-service
- product-service
- merchant-limit-service
- risk-management-service
- MySQL, Redis, Kafka, ZooKeeper

로컬 프로필의 PG 취소는 `MockPgCancelClient`를 사용한다. 따라서 내부 결제 취소 상태 전이와 후속 이벤트 흐름은 검증했지만 실제 Toss 카드 승인 취소는 검증 범위에 포함되지 않는다.

## 3. 정상 흐름 검증 결과

### 3.1 실행 시나리오

1. 구매자 계정으로 주문목록을 연다.
2. 결제 건에 취소 사유를 입력해 취소를 요청한다.
3. 관리자 계정으로 취소 요청 목록을 연다.
4. 요청을 승인한다.
5. 결제 DB에서 승인, 취소 요청, 결제 및 아웃박스 상태를 확인한다.
6. 주문 DB에서 주문과 주문상품 취소 상태를 확인한다.
7. 상품 DB에서 재고 수량, 예약 상태, 이벤트 멱등 처리 기록을 확인한다.
8. 새 구매자 브라우저 세션에서 주문목록의 `취소됨` 표시를 확인한다.

### 3.2 확인된 상태

취소 요청 ID `29`를 기준으로 다음 상태가 확인됐다.

```text
approval_status = APPROVED
cancel_status   = COMPLETED
payment_status  = CANCELLED
item_status     = CANCELLED
event_status    = PUBLISHED
event_retry     = 0
last_error      = NULL
```

상품 서비스에서는 SKU 1의 재고가 483에서 484로 증가했고, 결제키에 연결된 예약 1개가 `RELEASED`로 전환됐다. `processed_cancel_event`에도 취소 요청 ID `29`가 기록돼 동일 이벤트의 중복 소비로 인한 과다 복원을 방지한다.

주문 서비스의 주문 ID `33`과 주문상품 ID `34`도 모두 `CANCELLED`로 전환됐다.

## 4. 발견사항

### 4.1 [Critical] DEAD 취소 아웃박스가 실제 데이터 불일치를 만들고 있다

#### 증거

다음 두 취소 아웃박스가 `DEAD` 상태로 남아 있다.

| outbox ID | cancel request ID | payment key | retry | 결제 | 주문 | 재고 예약 |
| --- | ---: | --- | ---: | --- | --- | --- |
| 6 | 27 | `pay_191cf929ae6047698169` | 9 | `CANCELLED` | `DELIVERY_WAITING` | `RESERVED` |
| 7 | 28 | `pay_364dbc837b5a487b93ae` | 9 | `CANCELLED` | `DELIVERY_WAITING` | `RESERVED` |

두 이벤트 payload에는 SKU 1, 수량 1이 포함돼 있지만 상품 서비스의 `processed_cancel_event`에는 처리 기록이 없다. 주문상품도 각각 `ACTIVE` 상태다.

#### 원인

`CancelEventOutboxPublisher`는 최대 재시도 횟수에 도달한 이벤트를 `DEAD`로 바꾼다. 이후 폴러는 `PENDING`만 조회하므로 DEAD 이벤트는 영구적으로 처리 대상에서 제외된다.

운영 알림 구현도 현재 로그 출력에 한정돼 있다. 지속적인 메트릭, 외부 알림, 관리 화면 또는 재발행 명령이 없다. 두 DEAD 행의 `last_error`도 `NULL`이라 당시 실패 원인을 현재 데이터만으로 복원할 수 없다.

#### 영향

- 고객 화면에서는 결제가 취소됐지만 주문이 배송 대기 상태로 보일 수 있다.
- 판매 가능한 재고가 실제보다 적게 유지된다.
- 결제, 주문, 상품 서비스의 진실이 서로 달라진다.
- 운영자가 로그를 놓치면 불일치가 무기한 지속된다.

#### 권고

1. DEAD 이벤트 조회 및 통제된 재발행 기능을 추가한다.
2. 재발행 작업에는 실행자, 사유, 실행 시각, 결과를 감사 로그로 남긴다.
3. `cancel_event_outbox{status="DEAD"}` 게이지와 oldest-age 메트릭을 제공한다.
4. 로그 전용 알림을 Slack/PagerDuty 등 실제 운영 채널에 연결한다.
5. 예외 클래스와 root cause 메시지를 `last_error`에 보존한다.
6. 현재 DEAD 2건은 payload를 검증한 뒤 재발행하고 주문·재고 수렴을 재확인한다.

소비자 측에 멱등 게이트가 있으므로 동일 이벤트 재발행은 비교적 안전하지만, 운영 재발행 전에 payload와 대상 상태를 반드시 확인해야 한다.

### 4.2 [High] 승인과 반려의 동시 실행을 직렬화하지 않는다

#### 증거

승인과 반려는 모두 다음 순서로 처리된다.

1. 승인 요청을 조회한다.
2. 메모리에서 `REQUESTED`인지 확인한다.
3. 상태를 변경한다.
4. 엔티티 전체를 저장한다.

`cancel_approval` 테이블과 JPA 엔티티에는 `@Version`, 조건부 상태 변경, 행 잠금이 없다. `REQUESTED` 중복 생성을 차단하는 DB 불변식도 없다.

#### 위험

- 두 관리자가 동시에 승인과 반려를 누르면 마지막 저장이 앞선 결정을 덮을 수 있다.
- 결제 취소는 실행됐는데 승인 레코드는 `REJECTED`가 될 수 있다.
- 동시에 제출된 구매자 요청이 복수의 `REQUESTED` 행을 만들 수 있다.

#### 권고

- `UPDATE cancel_approval ... WHERE id = ? AND status = 'REQUESTED'` 형태의 CAS를 사용한다.
- affected row가 0이면 이미 결정된 요청으로 응답한다.
- 가능하면 optimistic locking용 `version` 컬럼을 추가한다.
- 활성 요청 중복은 생성 시점에도 DB 수준에서 직렬화한다.
- 승인 대 승인, 승인 대 반려, 동시 요청 생성 테스트를 추가한다.

### 4.3 [High] 모든 `DataIntegrityViolationException`을 중복 이벤트로 ACK한다

#### 증거

`PaymentCancelledStockConsumer`는 `DataIntegrityViolationException`을 받으면 원인을 구분하지 않고 중복 처리로 간주한 뒤 Kafka offset을 ACK한다.

#### 위험

이 예외는 `processed_cancel_event`의 unique key 충돌뿐 아니라 FK, 컬럼 길이, 제약조건 위반 등에서도 발생할 수 있다. 실제 데이터 오류를 중복 이벤트로 오인하면 메시지가 retry/DLQ로 이동하지 않고 유실된다.

#### 권고

- 예외 후 동일 `cancelRequestId`가 실제 처리 완료됐는지 재조회한다.
- 처리 기록이 존재하는 unique 충돌만 멱등 성공으로 ACK한다.
- 그 외 무결성 오류는 retry 또는 non-retryable DLQ로 라우팅한다.
- unique 충돌과 일반 무결성 오류를 분리한 소비자 테스트를 추가한다.

### 4.4 [High] 취소 대상 ID 검증이 PG 호출보다 늦게 완성된다

#### 증거

사전 검증과 취소 금액 계산은 결제에 존재하는 상품만 필터링한다. 요청된 모든 payment item ID가 실제 결제에 속하는지 비교하지 않는다. 존재하지 않는 ID의 최종 검증은 PG 호출 후 TX3의 도메인 서비스에서 수행된다.

#### 위험

정상 ID와 잘못된 ID가 섞인 직접 취소 요청은 다음 순서로 실패할 수 있다.

1. 존재하는 상품만 합산해 Risk 및 PG 취소를 호출한다.
2. PG 취소가 승인된다.
3. TX3에서 존재하지 않는 상품 ID가 발견돼 롤백된다.
4. 외부 PG와 내부 결제 상태가 불일치한다.

승인 워크플로우는 서버가 전체 payment item ID를 구성하므로 현재 정상 시나리오에는 노출되지 않는다. 다만 ADMIN/MERCHANT 직접 취소 API에는 방어가 필요하다.

#### 권고

- Risk/PG 호출 전에 요청 ID 집합과 조회된 대상 ID 집합이 정확히 일치하는지 검증한다.
- 빈 대상, 존재하지 않는 대상, 다른 결제의 대상, 정상·비정상 혼합 요청 테스트를 추가한다.
- 취소 금액이 0 이하이면 외부 호출 전에 거부한다.

### 4.5 [Medium] 재고가 복원되지 않아도 이벤트를 처리 완료로 기록할 수 있다

#### 증거

`StockService.release()`는 예약이 없거나 이미 해제된 경우 `releaseIfReserved()` 결과 0을 무시한다. `ProcessCancelledStockService`는 이후에도 `processed_cancel_event`를 저장한다. 또한 복원 수량은 예약 테이블의 수량이 아니라 취소 이벤트의 `quantity`를 사용한다.

#### 위험

- 예약 누락이나 비정상 상태가 조용히 성공 처리된다.
- 이벤트 수량과 예약 수량이 다르면 과소 또는 과다 복원될 수 있다.
- 처리 완료 기록 때문에 동일 이벤트를 다시 실행해도 복구되지 않는다.

#### 권고

- 복원 결과를 SKU별로 반환하고 기대한 예약이 없으면 불변식 위반으로 처리한다.
- 복원 수량은 가능하면 `stock_reservation.qty`를 기준으로 사용한다.
- `product_stock` UPDATE 결과가 0인 경우에도 실패로 처리한다.
- 이미 `RELEASED`인 정상 중복과 예약 누락을 구분한다.
- 불변식 위반은 DLQ 및 운영 알림으로 연결한다.

### 4.6 [Medium] 승인 상태와 취소 실행 상태의 의미가 분리돼 있지 않다

관리자 승인 후 PG가 `FAILED` 또는 장기 `PENDING`을 반환해도 승인 레코드는 `APPROVED`로 저장될 수 있다. 주문목록 조회는 `APPROVED` 상태를 별도 표시하지 않고 결제가 이미 취소됐다고 가정한다.

권고안은 다음 두 가지 중 하나다.

- 승인 결정과 취소 실행 상태를 화면/API에서 명시적으로 분리한다.
- 또는 승인 API가 최종 취소 성공 이후에만 `APPROVED`가 되도록 상태 모델을 재정의한다.

비동기 PG를 고려하면 첫 번째 방식이 더 명확하다. 예를 들어 `APPROVED/EXECUTING`, `APPROVED/FAILED`, `APPROVED/COMPLETED`처럼 결정과 실행 결과를 별도 필드로 제공할 수 있다.

### 4.7 [Low] 전체 취소 흐름을 고정하는 단일 자동화가 없다

현재 테스트는 개별 계층을 잘 다룬다.

- 취소 코어 멱등성 및 동시 실행
- 아웃박스 발행 재시도와 DEAD 전환
- 재고 복원 중복 방지와 부분취소
- 구매자 취소 요청 UI
- 관리자 승인/반려 UI

하지만 관리자 승인 E2E는 구매자 요청을 화면이 아닌 API로 만들며, 승인 후 행이 사라지는 것까지만 확인한다. 주문 취소, 재고 복원, 구매자 주문목록 반영을 한 테스트에서 검증하지 않는다.

다음 시나리오를 시스템 통합 테스트로 추가하는 것이 좋다.

```text
구매 → 구매자 화면 취소 요청 → 관리자 화면 승인
  → payment CANCELLED
  → outbox PUBLISHED
  → order CANCELLED
  → stock reservation RELEASED 및 available_qty 복원
  → 구매자 주문목록 취소됨 표시
```

## 5. 우선순위 제안

### P0: 현재 데이터 수렴

- DEAD outbox ID 6, 7의 payload와 대상 상태를 재검증한다.
- 두 이벤트를 통제된 방식으로 재발행한다.
- 주문 ID 27, 31이 `CANCELLED`로 수렴하는지 확인한다.
- 두 결제키의 재고 예약이 `RELEASED`로 바뀌고 SKU 1 재고가 총 2개 복원되는지 확인한다.
- 복구 전후 DB 스냅샷과 실행자를 기록한다.

### P1: 재발 방지

- DEAD 조회·재발행 기능과 실제 운영 알림을 추가한다.
- 승인/반려 CAS 및 중복 요청 방지 불변식을 추가한다.
- 상품 소비자의 무결성 예외 분류를 수정한다.
- PG 호출 전 취소 대상 전체를 검증한다.

### P2: 탐지 및 운영성

- DEAD/PENDING oldest-age, 처리 지연, 서비스 간 불일치 메트릭을 추가한다.
- 취소 요청 ID 또는 결제키로 결제·주문·재고 상태를 한 번에 조회하는 운영 도구를 제공한다.
- 재고 복원 결과를 구조화 로그와 트레이스에 남긴다.

### P3: 회귀 방지

- 전체 취소 흐름 시스템 테스트를 추가한다.
- 승인/반려 및 요청 생성 경합 테스트를 추가한다.
- 일반 무결성 오류가 ACK되지 않는 테스트를 추가한다.
- 실제 Toss sandbox 또는 계약 테스트를 별도 파이프라인으로 구성한다.

## 6. 완료 조건

다음 조건을 모두 만족하면 이번 리뷰의 핵심 개선이 완료된 것으로 판단한다.

- 결제 `CANCELLED`이면서 주문이 `DELIVERY_WAITING`인 취소 건이 0건이다.
- 결제 취소 이벤트가 완료됐지만 대상 재고 예약이 `RESERVED`인 건이 0건이다.
- `cancel_event_outbox.status = 'DEAD'` 건이 즉시 경보되고 운영자가 재처리할 수 있다.
- DEAD 재발행은 감사 이력을 남기며 중복 실행해도 주문과 재고가 과다 변경되지 않는다.
- 동일 승인 요청의 승인/반려 경합에서 정확히 하나의 결정만 성공한다.
- 존재하지 않는 payment item ID는 Risk 및 PG 호출 전에 거부된다.
- 일반 데이터 무결성 오류는 중복 이벤트로 ACK되지 않는다.
- 전체 E2E가 구매자 화면의 최종 `취소됨` 표시와 DB 수렴까지 검증한다.

## 7. 테스트 실행 결과

리뷰 중 다음 선별 테스트를 실행했고 모두 성공했다.

```text
:payment-service:test
  - CancelApprovalServiceTest
  - CancelPaymentServiceTest
  - CancelEventOutboxPublisherTest

:product-service:test
  - RetryRouterTest
```

테스트 성공은 현재 구현된 정상·예상 실패 시나리오가 유지된다는 의미다. 이 문서의 주요 발견사항은 기존 테스트가 다루지 않는 운영 수렴, 경합, 예외 분류 및 교차 서비스 불변식에 관한 것이다.

## 8. 관련 코드

- `payment-service/.../CancelApprovalService.java`
- `payment-service/.../CancelPaymentService.java`
- `payment-service/.../CancelTxWriter.java`
- `payment-service/.../CancelEventOutboxPublisher.java`
- `payment-service/.../CancelEventOutboxRepositoryImpl.java`
- `payment-service/.../LogOperationAlertAdapter.java`
- `product-service/.../PaymentCancelledStockConsumer.java`
- `product-service/.../ProcessCancelledStockService.java`
- `product-service/.../StockService.java`
- `frontend/e2e/cancel-approval.spec.js`
- `frontend/e2e/cancel-request.spec.js`

## 9. 변경 범위

이번 작업에서는 리뷰 문서만 추가했다. 애플리케이션 소스, DB 데이터, DEAD 이벤트 상태는 변경하지 않았다.
