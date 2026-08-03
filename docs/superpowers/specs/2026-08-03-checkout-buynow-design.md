# 체크아웃 P1 — 바로구매 종단간 설계 (2026-08-03)

스토어프론트에 **정방향 구매 흐름**(상품 조회 → 주문 → 결제)을 붙인다. 이 시스템은 원래 결제 취소(역방향) 중심이나, 주문·결제 **생성** 백엔드가 이미 존재하고 게이트웨이로 노출돼 있음을 실증했다. P1은 그 위에 **바로구매** 프론트를 얹어 실제로 결제까지 동작하게 한다.

전체 커머스 체크아웃은 단계화한다: **P1 바로구매(본 문서)** → P2 장바구니(교차상품·SKU 단위 다중) → P3 주문내역(+조회 API). 각 단계는 그 자체로 동작하는 제품이며 각각 spec→plan→구현 사이클을 갖는다.

## 실증으로 확정된 사실 (2026-08-03, 로컬 스택)

- **흐름 동작**: `POST /v1/orders`(orderId·orderItemIds 반환) → `POST /v1/payments`(orderItemId로 order-link 검증 + product 재고 예약 + 동기 `COMPLETED`). PG는 목이라 결제 생성 시 호출조차 없음 — 결제창/비동기 없음.
- **금액 규약**: `itemAmount=29000, quantity=2`로 보내니 `totalAmount=29000`(단가 그대로), 재고는 2 소진(9→7). 즉 **`quantity`는 금액에 곱해지지 않고 재고 소진에만 사용**. `totalAmount = Σ itemAmount`.
  - ⇒ 프론트는 **`itemAmount = 단가 × 수량`(라인 합계)** 을 계산해 전송해야 정확히 청구된다.
- **주문 모델 제약**: 주문 아이템은 `{productId, itemName, price}`만 가지며 **skuId·수량이 없다**(수량·skuId는 결제 아이템에만). SKU 단위 주문은 "라인 1개 = 주문아이템 1개 + 결제아이템 1개"로 매핑한다.
- **skuId 노출 갭**: 결제 생성엔 numeric `skuId`가 필수인데 `GET /v1/products/{id}`는 `skuCode`(문자열)만 노출한다 → **백엔드 변경 필요**.
- **인증**: 주문·결제 생성은 `X-User-Id`만 요구(역할 무관). 게이트웨이 인증 라우트라 로그인 쿠키 → X-User-Id 주입. 변경계열이라 CSRF 토큰 필요.

## 범위 / 논-골

**P1 포함**
- 상품 상세에서 그 상품의 SKU별 수량 선택 → "구매하기".
- 한 상품 상세 안에서 여러 SKU 라인 동시 구매 가능(같은 상품).
- 주문하기(체크아웃) 페이지: 라인 목록·수량·단가·라인합계·총액 확인 → "결제하기".
- 결제 완료 화면: paymentKey·총액·상태(COMPLETED).
- 로그인 필수(비로그인 시 로그인 유도).

**P1 논-골**
- 장바구니(교차상품·로컬 상태) — P2.
- 주문내역 페이지 + 주문/결제 조회 GET API — P3.
- 배송지/쿠폰/실 PG 연동 — 범위 밖.
- 재고 부족·결제 실패의 정교한 UX는 최소 에러 표시로만(백엔드가 fail-closed 반환).

## 백엔드 변경 (딱 1개)

**`GET /v1/products/{id}` 응답에 numeric `skuId` 추가** (product-service)
- `ProductDetailResponse`의 sku 항목에 `skuId`(Long) 필드 추가. 기존 `skuCode, optionSummary, availableQty, price, variant`는 유지.
- 매퍼가 도메인/엔티티의 sku id를 채운다. 취소 코어·다른 서비스 무변경.
- 프론트가 이 skuId로 결제 아이템을 구성한다.

## 프론트엔드 설계 (스토어프론트)

스토어프론트는 라우터 없이 `App.jsx`의 뷰 상태로 화면을 전환한다(어드민만 react-router). 이 방식을 유지한다.

**뷰 상태 확장** — `App.jsx`
- 기존: `{name:'home'}` | `{name:'detail', id}`
- 추가: `{name:'checkout', lines}` | `{name:'success', payment}`
  - `lines`: 범용 라인 목록 `[{skuId, productId, itemName, optionSummary, unitPrice, quantity}]` — P2 장바구니도 동일 형태를 checkout에 넘겨 **Checkout 재사용**.

**`ProductDetail.jsx` (수정)**
- SKU 표 각 행에 수량 입력(기본 0, max = availableQty).
- "구매하기" 버튼: 수량>0인 행들을 `lines`로 조립.
  - 비로그인(`!me`)이면 AuthModal 오픈(로그인 후 재시도).
  - 라인이 비어있으면(수량 0) 비활성/경고.
- 조립한 lines로 `onBuy(lines)` → App이 `{name:'checkout', lines}`로 전환.

**`Checkout.jsx` (신규)**
- props: `lines`, `me`, `onPaid(payment)`, `onBack`.
- 라인별 표시: itemName · optionSummary · 수량 · 단가 · 라인합계(단가×수량). 하단 총액 = Σ 라인합계.
- "결제하기" 클릭 시 (아래 API 시퀀스):
  1. `api.createOrder({ items: lines.map(l => ({ productId: l.productId, itemName: `${l.itemName} ${l.optionSummary}`.trim(), price: l.unitPrice * l.quantity })) })` → `{orderId, items:[{orderItemId, itemName}]}`.
  2. orderItemId를 라인 순서대로 매핑(orders 응답 items 순서 = 요청 순서 가정; 안전하게 index로 zip).
  3. `api.createPayment({ merchantId: 1, pgType: 'TOSS', cancelPeriodDays: 7, items: lines.map((l,i) => ({ orderItemId: order.items[i].orderItemId, productId: l.productId, itemName: `${l.itemName} ${l.optionSummary}`.trim(), itemAmount: l.unitPrice * l.quantity, skuId: l.skuId, quantity: l.quantity })) })` → `{paymentKey, totalAmount, status, ...}`.
  4. 성공 → `onPaid(payment)` → App `{name:'success', payment}`.
  - 실패(4xx/5xx: 재고 부족·order-link 등) → 에러 메시지 표시, 체크아웃 유지.
- `merchantId=1`은 P1 고정(데모). 상품↔가맹점 매핑은 범위 밖.

**`OrderSuccess.jsx` (신규)**
- props: `payment`, `onHome`.
- 표시: "결제 완료" + paymentKey + totalAmount + status(COMPLETED) + 구매 항목 목록. "쇼핑 계속하기" → 홈.

**`api.js` (수정)** — `export const api`에 추가:
- `createOrder: (b) => req('/v1/orders', { method: 'POST', body: b, csrf: true })`
- `createPayment: (b) => req('/v1/payments', { method: 'POST', body: b, csrf: true })`
- (기존 `product(id)`는 이미 있음 — 응답에 skuId가 추가되면 프론트가 사용)

## 인증 / 게이트웨이

- `POST /v1/orders`·`POST /v1/payments/**`는 이미 게이트웨이 인증 라우트(JwtTrustHeaderFilter → X-User-Id 주입). 로그인 세션 쿠키로 호출.
- 변경계열이므로 게이트웨이 CsrfFilter가 CSRF 검증 → `api.js`의 `csrf:true`(csrf_token 쿠키 == X-CSRF-Token 헤더).
- order-link 불변식: payment의 orderItemId들은 **같은 유저의 한 주문**에 속해야 함. 프론트가 방금 만든 주문의 orderItemId만 사용하므로 자연 충족(P1은 단일 주문 = 단일 상품의 라인들).

## 데이터 흐름 요약

```
ProductDetail (SKU 수량 선택)
  └─ 구매하기 → lines[{skuId,productId,itemName,optionSummary,unitPrice,quantity}]
       └─ Checkout (총액 확인)
            └─ 결제하기
                 ├─ POST /v1/orders  {items:[{productId,itemName,price=단가×수량}]}  → orderItemIds
                 └─ POST /v1/payments {items:[{orderItemId,productId,itemName,itemAmount=단가×수량,skuId,quantity}]}
                      → {paymentKey,totalAmount,status:COMPLETED}  (+ 재고 quantity 소진)
                           └─ OrderSuccess
```

## 테스트

**백엔드 (product-service, 단위)**
- `GET /v1/products/{id}` 응답 sku 항목에 `skuId`(numeric)가 포함되고 값이 정확한지.

**프론트 E2E (Playwright, 실 스택)**
- 저니: 로그인 → 상품 상세 → SKU 수량 선택 → 구매하기 → 체크아웃 총액 확인 → 결제하기 → 완료 화면(paymentKey 노출) → 재고가 quantity만큼 감소.
- 금액 검증: 단가×수량이 총액과 일치(수량>1 케이스 포함).
- 비로그인 구매하기 → 로그인 유도.

## 트레이드오프 / 메모

- **주문 item에 수량 없음**: 백엔드 한계라 P1은 "라인=주문아이템 1개" 매핑으로 수용. 주문 도메인에 수량을 추가하는 것은 범위 밖(취소 코어 영향 회피).
- **merchantId·cancelPeriodDays 고정값**: 데모 상수. 실 상품↔가맹점/정책 매핑은 후속.
- **주문/결제 조회 부재**: 완료 화면은 생성 응답만으로 구성. 영속 "주문내역"은 P3에서 조회 API와 함께.
- **Checkout 범용 라인 입력**: P2 장바구니가 동일 `lines` 형태를 넘기면 Checkout/Success를 그대로 재사용 — P1 설계가 P2를 준비한다.
