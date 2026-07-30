# SKU 재고 수명주기 (최소 카탈로그) — Design Spec

- **작성일:** 2026-07-30
- **상태:** 승인됨 (brainstorming) → gsd 마일스톤 계획 대기
- **범위:** product-service 구축 경로 Y(목표-우선 수직 슬라이스)의 **서브프로젝트 1**. category(대중소)·attribute·image·version 풀 카탈로그는 후속 서브프로젝트로 백필.
- **원 요구:** "결제 취소 시 재고를 복원하고 싶다" → SKU 단위 재고 수명주기로 확장.

---

## 1. 배경 & 목표

현재 결제 취소는 `payment.cancelled` 이벤트를 OUTBOX로 발행하고 order-service가 구독해 주문 상태를 동기화한다. product-service는 **빈 껍데기**(소스·DDL 없음)이며, 재고 개념·수량(quantity)·SKU가 시스템 어디에도 없다.

**목표(서브프로젝트 1):** 결제 생성 시 SKU 재고를 **동기 예약(reserve)** 하고, 취소 시 그 SKU 재고를 **복원(release)** 한다. 카탈로그는 재고에 필요한 최소(product·SKU·stock)만 세운다.

**설계 원칙:** 이 시스템에 이미 있는 **reserve/compensate 패턴**(risk-management의 취소 한도 예약·보상)을 미러링한다. 검증된 아키텍처를 재사용하고 **취소 코어(멱등성·TX1/2/3·스케줄러·outbox)는 불변**으로 둔다.

---

## 2. 범위

### In scope (서브프로젝트 1)
- product-service 최소 구축: 헥사고날 레이어, 독립 MySQL, Flyway V1. `product`·`product_sku`·`product_stock`·`stock_reservation`.
- 재고 reserve/release 엔드포인트(멱등·원자, 오버셀 방지).
- 최소 등록 경로(`POST /v1/products` — product+SKU+초기 재고 seed).
- payment 생성 흐름에 `skuId`+`quantity` 배선(요청~커맨드~엔티티~DDL V16).
- payment→product 동기 예약 클라이언트(Port/Adapter + CircuitBreaker) + reserve hook + 보상.
- `payment.cancelled` payload에 `skuId`+`quantity` 추가(하위호환).
- product의 `payment.cancelled` 신규 consumer(재고 복원, 멱등·부분취소 인지) + orphan 예약 복구 스케줄러.

### Out of scope (후속 서브프로젝트로 백필)
- category(대·중·소 계층), product_attribute_type/value, product_sku_attribute(변형 속성 정규화), product_image, product_version.
- 상품 조회/검색/브라우징 API, 관리자 카탈로그 UI.
- 재고 예약의 명시적 confirm 단계(saga 3-phase) — YAGNI, 예약=차감 모델 채택.

### Non-goals
- 취소 코어 로직 변경(payload 필드 추가 외).
- product-service의 order/merchant 등 타 서비스 연동(재고는 payment 경로만).

---

## 3. 아키텍처

```
[결제 생성]  client ── POST /v1/payments (items[skuId, quantity]) ──▶ payment-service
   payment-service:
     1) product.reserve(paymentKey, items[skuId,qty])  ── 동기 HTTP ──▶ product-service
          재고 부족 → 409 → 결제 생성 거부 (오버셀 방지)
     2) reserve 성공 → payment + payment_item(skuId,quantity) TX 커밋
     3) reserve 성공 후 TX 실패 → product.release(paymentKey) 보상(+재시도 스케줄러)

[취소]  기존 취소 플로우(불변) ── TX3 ──▶ cancel_event_outbox(payment.cancelled, +skuId+quantity)
   → Kafka payment.cancelled
   → product-service 신규 consumer(group=product-service)
        → 취소된 items의 skuId+qty 만큼 release (부분취소 인지, 멱등)

[복구]  product orphan 스케줄러 ── reserve 성공 후 payment 미생성된 오래된 RESERVED ──▶
        payment-service 조회("paymentKey 커밋됨?") → 없으면 release
        (processing-recovery의 PG 조회 패턴 재사용)
```

미러링 대상: `RiskManagementHttpClient`(RestTemplate + Resilience4j CircuitBreaker + Port/Adapter), 취소 플로우의 TX1→risk.reserve→TX2 규율(HTTP는 TX 밖).

---

## 4. 데이터 모델

### product-service (신규, Flyway V1)
```sql
product(
  id BIGINT PK AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  created_at, updated_at
)
product_sku(
  id BIGINT PK AUTO_INCREMENT,
  product_id BIGINT NOT NULL,   -- FK product.id
  sku_code VARCHAR(64) NOT NULL UNIQUE,
  option_summary VARCHAR(255),  -- 예: "M/Black" (정규화는 백필)
  created_at, updated_at,
  INDEX idx_product_sku_product (product_id)
)
product_stock(
  sku_id BIGINT PK,             -- FK product_sku.id (1:1)
  available_qty INT NOT NULL,   -- 가용 재고 (오버셀 방지 원자 UPDATE 대상)
  updated_at
)
stock_reservation(
  id BIGINT PK AUTO_INCREMENT,
  payment_key VARCHAR(255) NOT NULL,
  sku_id BIGINT NOT NULL,
  qty INT NOT NULL,
  status VARCHAR(20) NOT NULL,  -- RESERVED / RELEASED
  created_at, updated_at,
  UNIQUE uk_reservation_paymentkey_sku (payment_key, sku_id),  -- 멱등
  INDEX idx_reservation_status_created (status, created_at)     -- orphan 스캔
)
```
- **오버셀 방지**: reserve = `UPDATE product_stock SET available_qty = available_qty - :qty WHERE sku_id=:id AND available_qty >= :qty` (affected rows 0 → 부족 → 409). risk 한도 원자 차감과 동형.
- **멱등**: `uk_reservation_paymentkey_sku` — 같은 paymentKey+sku 재요청은 재차감 안 함.

### payment-service (변경, Flyway V16)
```sql
ALTER TABLE payment_item
  ADD COLUMN sku_id BIGINT NULL,      -- product_sku.id 참조(느슨 결합, FK 아님 — 모듈 격리)
  ADD COLUMN quantity INT NOT NULL DEFAULT 1;
```
- `sku_id` NULL 허용: 기존 데이터 하위호환(재고 없이 생성된 과거 건). 신규 생성은 필수 검증.
- `product_id`는 유지(기존). 재고 키는 `sku_id`.

---

## 5. API 계약 (product-service)

```
POST /v1/products            # 최소 등록(seed)
  req:  { name, skus:[{ skuCode, optionSummary, initialStock }] }
  res:  { productId, skus:[{ skuId, skuCode }] }

POST /v1/stock/reserve       # 멱등(paymentKey), 원자, 오버셀 방지
  req:  { paymentKey, items:[{ skuId, qty }] }
  res:  200 { reserved:true } | 409 STOCK_INSUFFICIENT { skuId, available }
  규칙: 전 items 원자 처리(하나라도 부족하면 전체 실패·롤백), paymentKey 중복은 기존 예약 재사용(200)

POST /v1/stock/release       # 멱등
  req:  { paymentKey, items:[{ skuId, qty }] }
  res:  200 { released:true }
  규칙: RESERVED만 해제(available += qty, status→RELEASED). 이미 RELEASED면 no-op 200
```

---

## 6. 이벤트 payload 변경 (payment.cancelled)

`CancelTxWriter.buildPayload`의 `cancelledItems[]`에 필드 추가:
```
cancelledItems: [{ paymentItemId, orderItemId, itemAmount, skuId, quantity }]
```
- **하위호환 추가**(kafka-design.md 규약: 필드 추가는 즉시 배포 가능). order consumer는 신규 필드 무시.
- 파티션 키·토픽·outbox 스키마 무변경(payload는 불투명 JSON).
- **TX 경계·멱등 로직 불변** — payload 빌더에 필드만 추가.

---

## 7. payment 통합 (reserve hook + 보상)

- **위치**: `CreatePaymentService.create()` — payment TX **앞**에서 `ProductStockPort.reserve(paymentKey, items)` 동기 호출. 성공해야 TX 진행(취소 플로우의 TX-밖-HTTP 규율과 동형).
- **paymentKey 생성 타이밍(주의)**: 예약 키가 `paymentKey`이므로 **reserve 호출 전에 paymentKey가 확정**돼야 한다. 현재 생성 로직이 paymentKey를 TX 안(엔티티 저장 시점)에서 생성한다면, 생성을 reserve 앞으로 이동해야 한다. 실제 생성 시점은 계획 단계에서 `CreatePaymentService` 확인 후 확정(§12).
- **보상**: reserve 성공 후 payment TX 실패 → `ProductStockPort.release(paymentKey, items)` best-effort + 실패 시 재시도 스케줄러(compensation-retry 동형, payment-service Redis 분산락).
- **클라이언트**: `ProductStockPort`(application/interfaces) + `ProductStockHttpClient`(infrastructure/http, RestTemplate + 전용 Resilience4j CircuitBreaker, `RiskManagementHttpClient` 복제) + `external.product-service.url`.
- **CircuitBreaker OPEN 정책**: product 장애 시 결제 생성 실패로 처리(fail-closed) — 오버셀 방지가 가용성보다 우선(리뷰 필요, §12).

---

## 8. product 취소 consumer + 복구

- **consumer**: `@KafkaListener(topics=payment.cancelled, groupId=product-service)`. `cancelledItems`의 `skuId+quantity`만큼 `release`. **부분취소 인지**(전량 아님). 실패 시 retry/DLQ 라우팅(order consumer의 RetryRouter 패턴).
- **멱등**: `processed_cancel_event(cancel_request_id UK)` 신규 테이블 — 중복 이벤트 no-op(order consumer 패턴 복제).
- **orphan 복구 스케줄러**: `stock_reservation` 중 오래된(예: 5분+) `RESERVED`를 payment-service에 조회(`GET /v1/payments/{paymentKey}` 또는 존재 확인 API) → 커밋된 payment 없으면 release. processing-recovery(PG 조회)와 동형. Redis 분산락.

---

## 9. 불변식 & 일관성

- **오버셀 방지**: reserve 원자 조건부 UPDATE(available >= qty).
- **멱등**: reserve/release = paymentKey+sku UK. 취소 consumer = cancelRequestId UK.
- **부분취소**: 취소 이벤트의 `cancelledItems`만 해제. 재고 = 예약 qty 기준.
- **예약=차감, confirm 없음**: 해제 트리거는 (a)취소 이벤트 (b)생성 실패 보상 (c)orphan 타임아웃뿐. 결제 성공 시 별도 조치 없음.
- **취소 코어 불변**: payload 필드 추가 외 payment-service 취소 로직 무변경. `git diff` 코어 게이트(scheduler/messaging/TxWriter/서비스)로 강제.
- **모듈 격리**: payment↔product = HTTP(reserve/release) + Kafka(취소 이벤트)만. DB 직접 접근 없음. `sku_id`는 느슨 참조(FK 아님).

---

## 10. 테스트 전략

- product-service: reserve 오버셀(동시 요청 → 원자 UPDATE로 하나만 성공) 통합테스트(Testcontainers MySQL), reserve/release 멱등, 재고 부족 409.
- payment: reserve 성공/실패 시 생성 진행/거부, reserve 후 TX 실패 → release 보상 호출(Mockito), sku/quantity 영속화(V16), payload에 skuId/quantity 포함.
- product consumer: payment.cancelled → 정확한 sku/qty 복원, 부분취소, 멱등(중복 이벤트 no-op), orphan 스케줄러 복구.
- E2E(Testcontainers, 선택): 생성(reserve)→취소(release) 재고 왕복이 실제로 available_qty를 원복하는지.

---

## 11. 후속 서브프로젝트 (경로 Y 백필 로드맵)

1. **(본 spec) SKU 재고 수명주기** — 최소 product·SKU·stock + reserve/release + 취소 복원.
2. **카탈로그 코어 백필** — category(대·중·소 계층) + product 확장 + product_image.
3. **속성 시스템** — product_attribute_type/value + product_sku_attribute(변형 정규화: option_summary → 구조화).
4. **조회/브라우징** — 상품/카테고리 조회 API, 검색.

각 서브프로젝트는 독립 spec → gsd 마일스톤/phase.

---

## 12. 열린 질문 / 가정 (계획 전 확인)

- **CircuitBreaker fail-closed vs fail-open**: product 장애 시 결제를 막을지(오버셀 방지 우선) vs 통과시킬지(가용성 우선). 본 spec은 **fail-closed** 가정 — 사업 정책 확인 필요.
- **SKU 참조 방식**: payment_item에 `sku_id`(product 내부 id) vs `sku_code`(비즈니스 코드). 본 spec은 `sku_id` 가정(기존 product_id 저장 방식과 일관).
- **orphan 조회 API**: payment-service에 "paymentKey 존재 확인" 경량 엔드포인트 신설 필요(현재 없으면 추가).
- **초기 재고 seed 주체**: `POST /v1/products`로 수동 seed 가정. 대량 카탈로그 적재는 백필 서브프로젝트.
- **quantity 기본값**: 기존 payment_item 하위호환 위해 DEFAULT 1. 신규 생성은 명시 필수 검증.
- **paymentKey 생성 시점**: reserve(TX 앞)가 paymentKey를 키로 쓰므로 생성이 reserve보다 앞서야 함 — `CreatePaymentService`의 현재 paymentKey 생성 위치 확인 후, 필요 시 TX 앞으로 이동.
