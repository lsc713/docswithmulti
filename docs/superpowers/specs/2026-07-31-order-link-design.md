# 주문(order) → 결제 라이프사이클 링크 — 설계 (order-link)

> 작성 2026-07-31. 결제 생성 시 orderItemId를 order-service에 존재·소유 검증하고,
> payment에 `order_id` 강한 링크를 건다. 취소 코어(멱등·TX1/2/3·스케줄러·outbox)는 불변.

## 1. 목적 / 범위

- **목적**: 지금 payment는 `orderItemId`를 **검증 없이 신뢰**하고 payment↔order 링크(FK/order_id)가 없다. 결제를 상류 주문에 신뢰 가능하게 연결한다.
- **스코프(In)**:
  1. 결제 생성 시 `orderItemId`들을 order-service에 **존재 + 단일 order 소속 + 소유(order.user_id == 요청자)** 검증.
  2. payment에 **`order_id` 강한 링크**(NOT NULL) 추가.
  3. 결제 생성이 신원을 **X-User-Id 신뢰헤더**에서 취득(body `userId` 제거).
  4. order-service의 **기존 주문 생성**(`POST /v1/orders`)도 X-User-Id로 전환(body `userId` 제거) — 일관성.
- **Out of scope**:
  - 주문 생성이 결제/예약을 자동 트리거하는 오케스트레이션(order→payment 결합) — **디커플링 유지**. 클라이언트가 주문→결제를 순서대로 호출.
  - merchant_id 신뢰헤더화(결제 생성 body 유지) — 별도 항목.
  - 취소 코어 로직 일체.
- **시간 순서(불변)**: `클라 POST /v1/orders (주문 먼저)` → `클라 POST /v1/payments (결제 나중, 상류 주문 검증·링크)`.

## 2. 확정 결정 (브레인스토밍)

| 결정 | 값 |
|------|-----|
| 카디널리티 | 결제 1 : 주문 1 → `payment.order_id` 단일 NOT NULL |
| 소유 기준 | `order.user_id == X-User-Id`(신뢰헤더) + 모든 item 동일 order |
| 결제 생성 신원 | X-User-Id 신뢰헤더(body userId 제거) |
| 주문 생성 신원 | X-User-Id 신뢰헤더(body userId 제거) — 게이트웨이 라우트 필요 |
| 검증 방식 | 동기 HTTP, fail-closed (product reserve 패턴) |
| 검증 신원 전달 | body 아님 — **X-User-Id 헤더**(payment가 포워딩) |
| 결합 방식 | 검증+링크만, 디커플링 유지 |

## 3. 컴포넌트 책임

### order-service
- **신규 `POST /v1/orders/items:verify`** (내부, 게이트웨이 미노출):
  - Header `X-User-Id`, Body `{ orderItemIds: [long...] }`.
  - 판정: 모든 orderItemId 존재 && 전부 **동일 order** 소속 && 그 `order.user_id == X-User-Id`.
  - 성공 `200 { orderId }`(해석된 단일 order_id 반환) / 실패는 §6 에러.
  - 판정 로직은 도메인 순수(예: `OrderItemVerifier`), 컨트롤러는 헤더/DTO 매핑만.
- **기존 `POST /v1/orders` 변경**: `userId`를 body(`CreateOrderRequest.userId`)가 아닌 `@RequestHeader X-User-Id`에서 취득. `CreateOrderRequest`에서 `userId` 제거.

### api-gateway
- **신규 secured 라우트**: `POST /v1/orders`(+ 필요 시 `GET /v1/orders/{id}`) → order downstream, `JwtTrustHeaderFilter` 부착(검증→strip→X-User-Id 주입). **`/v1/orders/items:verify`는 노출하지 않음**(내부 전용) — 라우트 경로를 정확히 `/v1/orders`(및 `/v1/orders/{id}`)로 한정, `/**` 금지.
- CSRF: `POST /v1/orders`는 상태변경 → 브라우저(쿠키) 호출 시 X-CSRF-Token 필요(기존 CsrfFilter 규칙 그대로, Bearer는 면제).

### payment-service
- **신규 `OrderVerifyHttpClient`**: order-service `items:verify` 호출. **fail-closed** — order-service 장애/타임아웃/비200 → 결제 거부(`ProductStockHttpClient` 동일 스타일). 검증된 `orderId` 반환.
- **`CreatePaymentService` 흐름 변경**(검증 최전방):
  ```
  order 검증(신규, 부작용 없음) → paymentKey 발급 → 재고 예약(product) → persist(payment.order_id 포함)
  ```
  order 검증이 재고 예약(보상 필요한 부작용)보다 앞 → 실패 시 보상 불필요.
- **`PaymentController`**: `userId`를 `@RequestHeader X-User-Id`에서 취득, `CreatePaymentRequest.userId` 제거. 이 값을 order 검증에 그대로 포워딩(X-User-Id 헤더로).
- **스키마**: `payment.order_id BIGINT NOT NULL` + `INDEX idx_payment_order_id` — 신규 Flyway `V18`.

### 취소 코어 (payment)
- **불변**. `order_id`는 새 컬럼일 뿐 TX1/TX2/TX3·멱등·스케줄러 3종·outbox 무변경. CI 게이트로 코어 파일 무변경 강제(기존 관행).

## 4. 데이터 흐름

```
[주문 생성 — 먼저]
  클라 → GW POST /v1/orders (X-CSRF-Token, 쿠키/Bearer)
       → GW: JWT 검증 → X-User-Id 주입 → order-service
       → order-service: order+item 생성(user_id = X-User-Id)

[결제 생성 — 나중]
  클라 → GW POST /v1/payments {merchantId, pgType, items[...]}  (X-User-Id 주입됨)
       → payment CreatePaymentService:
         1) OrderVerifyHttpClient → order POST /v1/orders/items:verify (X-User-Id 포워딩, {orderItemIds})
              → 200 {orderId}  (존재+단일 order+소유)   / 4xx → 결제 거부
         2) paymentKey 발급
         3) product 재고 예약 (fail-closed)
         4) persist: payment(order_id=검증된 orderId, user_id=X-User-Id) + payment_item
```

## 5. 인증/신뢰 경계

- order-service가 게이트웨이에 노출되면서 **X-User-Id를 신뢰**하게 된다 → payment와 동일하게 **NetworkPolicy로 order(:8081) ingress를 게이트웨이 파드로만 제한** 필수(배포 게이트). 없으면 헤더 스푸핑으로 소유 검증 우회. (payment의 domain-rules §8-3와 동형.)
- `items:verify`는 게이트웨이 미노출·내부 전용. 호출자는 payment뿐이며 payment가 검증된 X-User-Id를 포워딩. order-service는 내부 호출의 X-User-Id를 신뢰(내부망 격리 전제).

## 6. 에러 처리 (error-catalog 확장)

| 상황 | 코드 | HTTP | 위치 |
|------|------|------|------|
| orderItemId 미존재 | `ORDER_ITEM_NOT_FOUND` | 404 | order verify |
| 여러 order에 걸침 | `ORDER_ITEMS_MULTIPLE_ORDERS` | 409 | order verify |
| 소유 불일치(order.user_id≠X-User-Id) | `ORDER_OWNERSHIP_MISMATCH` | 403 | order verify |
| X-User-Id 누락 | `TOKEN_MISSING`(기존) | 401 | gateway |
| order-service 장애/타임아웃 | `ORDER_VERIFY_UNAVAILABLE`(fail-closed) | 502/503 | payment |

payment는 verify 4xx를 결제 생성 실패로 매핑(사용자에게 사유 전달). 정확한 코드·메시지는 error-catalog.md에 추가.

## 7. 마이그레이션 / 배포

- **`V18__add_order_id_to_payment.sql`**: `ALTER TABLE payment ADD COLUMN order_id BIGINT NOT NULL, ADD INDEX idx_payment_order_id (order_id)`.
  - ⚠️ **기존 payment 행 존재 시 NOT NULL 추가 실패**. dev/포트폴리오 DB는 기존 데이터 backfill 불가(과거 결제의 order 미상). 두 가지 중 택:
    - (a) 신규 dev DB 전제 → NOT NULL 직행(권장, 데이터 없음 가정).
    - (b) 데이터 보존 필요 시: nullable 추가 → backfill(불가 시 sentinel) → NOT NULL 2단계.
  - 구현 시 실제 데이터 유무 확인 후 결정(gsd 플랜 단계에서 확정).
- 배포 게이트: order NetworkPolicy(§5) + 기존 JWT_SECRET 공유.

## 8. 테스트 전략

- **order-service**: `OrderItemVerifier` 도메인 단위(존재/단일order/소유 조합), verify 컨트롤러(X-User-Id 매핑, 200/404/409/403), 기존 create의 X-User-Id 전환(body userId 제거 회귀).
- **payment**: `OrderVerifyHttpClient` fail-closed(장애→거부), `CreatePaymentService` 흐름(verify 실패 시 재고예약 미호출·persist 미발생 / 성공 시 order_id 저장), `PaymentController` X-User-Id 매핑. **취소 코어 무회귀 게이트**.
- **gateway**: `/v1/orders` secured 라우트(무토큰 401, verify 경로 미노출 확인), CSRF 규칙.
- **통합**: order 생성 → payment 생성(검증 성공) 해피패스, 소유 불일치 결제 거부.

## 9. gsd 마일스톤

이 spec을 gsd 마일스톤 **`order-link`** 워크스트림으로 넘긴다(신규 feature). 워크스트림 = order-service verify+create 전환 / payment 링크·검증 / gateway 라우트·NetworkPolicy.
