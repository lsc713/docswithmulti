# 체크아웃 P2 — 서버 장바구니 설계 (2026-08-04)

체크아웃 단계화의 **P2**. P1(바로구매, PR #94)에 이어 **서버 영속 장바구니**를 추가한다. 로그인 유저가 여러 상품(교차 카테고리)의 SKU 라인을 담아두고, 장바구니 화면에서 수정한 뒤 한 번에 주문·결제한다. 체크아웃 자체는 P1의 `Checkout`(order → payment)을 **그대로 재사용**한다.

전제(P1에서 확정): 주문/결제 생성 백엔드는 **다중 아이템 리스트**를 받고, `GET /v1/products/{id}`가 numeric `skuId`를 노출하며, `Checkout`은 범용 `lines`(`[{skuId, productId, itemName, optionSummary, unitPrice, quantity}]`)를 입력으로 받는다. 금액 규약: `itemAmount`/order `price` = 단가 × 수량, `totalAmount = Σ itemAmount`, `quantity`는 재고 소진용.

## 결정 사항 (brainstorming)

- **저장 위치: 서버(백엔드 영속)**. 새로고침·탭 재방문에도 유지, 기기 간 동기화.
- **소유 서비스: order-service**. 장바구니 = pre-order draft로 보고 기존 order-service에 `cart_item` 테이블 + CRUD를 추가한다(신규 서비스 스캐폴딩 회피). 취소 코어·order 생성/검증 로직과 독립.

## 범위 / 논-골

**P2 포함**
- 로그인 유저 장바구니: 담기(SKU 라인) · 조회 · 수량 수정 · 라인 삭제 · 전체 비움.
- 여러 상품·교차 카테고리 SKU 라인 누적.
- 상품 상세 "장바구니 담기" 버튼(기존 "구매하기"와 병존).
- 네비바 장바구니 개수 + 장바구니 화면.
- 장바구니 → "주문하기" → 기존 Checkout(order+payment) → 성공 시 장바구니 비움.

**P2 논-골**
- 주문내역 페이지 + 주문/결제 조회 API — P3.
- 실 PG·배송지·쿠폰·상품↔가맹점 매핑(merchantId=1 데모 상수 유지).
- 담을 때 재고 실시간 검증/예약(재고 예약은 결제 시점 그대로) — 장바구니는 가격·옵션 **스냅샷** 저장.
- 비로그인 게스트 장바구니(로그인 필수).

## 백엔드 (order-service) — cart 도메인 추가

기존 hex 레이어(domain/entity · application/interfaces·service·usecase · infrastructure/persistence · presentation/controller·dto)를 그대로 따른다.

**V5 마이그레이션** `V5__create_cart.sql`:
```
cart_item(
  id BIGINT PK AUTO,
  user_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  item_name VARCHAR(255) NOT NULL,
  option_summary VARCHAR(255) NULL,
  unit_price BIGINT NOT NULL,
  quantity INT NOT NULL,
  created_at/updated_at DATETIME(6),
  UNIQUE KEY uk_cart_user_sku (user_id, sku_id)
)
```
(order-service Flyway 최신은 V4 → 다음 V5. V2는 원래 결번.)

**도메인/레이어**
- `domain/entity/CartItem`(POJO, JPA 어노테이션 금지).
- `application/interfaces/CartRepository` 포트: `findByUserId(long)`, `findByUserIdAndSkuId(long, long)`, `save(CartItem)`, `deleteByUserIdAndSkuId(long, long)`, `deleteByUserId(long)`.
- `application/usecase/CartUseCase` + `application/service/CartService`: `getCart`, `addItem`(있으면 수량 병합=기존+신규), `updateQuantity`(설정, 0 이하 거부), `removeItem`, `clear`.
- `infrastructure/persistence`: `CartItemJpaEntity`, `CartItemJpaRepository`(Spring Data), `CartRepositoryImpl`.
- `presentation/controller/CartController`(`/v1/cart`) + DTO.

**API** (전부 `@RequestHeader("X-User-Id") long userId`)
- `GET /v1/cart` → `{ items: [{ skuId, productId, itemName, optionSummary, unitPrice, quantity }] }` (user_id 필터, created_at 정렬).
- `POST /v1/cart/items` → 담기. body `{ skuId, productId, itemName, optionSummary, unitPrice, quantity }`. (user_id, sku_id) 존재 시 **quantity 병합**(기존+신규), 없으면 INSERT. 200/201 + 반영된 라인.
- `PATCH /v1/cart/items/{skuId}` → `{ quantity }`(설정값, >0). 없는 라인 404.
- `DELETE /v1/cart/items/{skuId}` → 204. (idempotent — 없어도 204/404 중 택1, 204 권장)
- `DELETE /v1/cart` → 204(전체 비움).
- 인가: `X-User-Id` 신뢰(게이트웨이 주입). 역할 무관(구매자 기능). 다른 유저 장바구니 접근 불가(항상 헤더의 user_id로만 쿼리).

**불변**: 취소 코어, `POST /v1/orders`·`items:verify`, order/order_item 테이블·로직 무변경.

## 게이트웨이

- `/v1/cart/**` 인증 라우트 추가(JwtTrustHeaderFilter → JWT 검증 + X-User-* strip/주입). `/v1/orders`(정확 predicate)·`items:verify`(미노출)와 무간섭.
- RouteConfig javadoc 갱신 + `GatewayRoutingIT`에 no-token 401 / valid-JWT 라우팅(+X-User-Id 주입) 테스트.
- 변경계열(POST/PATCH/DELETE)이라 CsrfFilter 적용 → 프론트 `csrf:true`.

## 프론트엔드 (스토어프론트)

**`api.js`**
- `getCart()` → GET /v1/cart
- `addCartItem(b)` → POST /v1/cart/items (csrf)
- `updateCartItem(skuId, quantity)` → PATCH /v1/cart/items/{skuId} (csrf)
- `removeCartItem(skuId)` → DELETE /v1/cart/items/{skuId} (csrf)
- `clearCart()` → DELETE /v1/cart (csrf)

**`App.jsx`** — 장바구니 상태(서버)
- 로그인 상태(`me`)면 마운트/로그인 시 `getCart()`로 로드, `cartCount` 유지.
- 뷰에 `cart` 추가. `handleAddToCart(lines)`: 비로그인 → AuthModal, else 각 라인 `addCartItem` 후 cart 새로고침.
- checkout 진입을 cart에서도 지원(cart의 lines를 checkout 뷰로). 결제 성공 `onPaid`에서 `clearCart()` + cart 새로고침 후 success.

**`ProductDetail.jsx`** — "장바구니 담기" 버튼 추가(기존 "구매하기" 유지). 선택 라인(qty>0)을 `onAddToCart(lines)`로.

**`NavBar.jsx`** — 로그인 시 `장바구니(n)` 버튼 → cart 뷰(`onCart`).

**`Cart.jsx`(신규)** — props: `items`, `onQty(skuId, q)`, `onRemove(skuId)`, `onOrder(lines)`, `onBack`.
- 라인 목록(itemName·optionSummary·단가·수량 입력·라인합계·삭제) + 총액.
- 수량 변경 → `updateCartItem` → 새로고침. 삭제 → `removeCartItem` → 새로고침.
- "주문하기" → cart items를 `lines`로 변환해 `onOrder(lines)`(App이 checkout 뷰로). 빈 장바구니면 비활성.

**Checkout 재사용**: 기존 `Checkout`(P1)에 cart lines를 넘긴다(코드 무변경). 성공 콜백에서 App이 `clearCart()`.

## 데이터 흐름

```
ProductDetail(수량선택) ─ 장바구니 담기 → POST /v1/cart/items (라인별, 병합)
NavBar 장바구니(n) → Cart 뷰 ← GET /v1/cart
  Cart: 수량 PATCH / 삭제 DELETE / 총액
    └ 주문하기 → lines → Checkout(재사용)
         ├ POST /v1/orders → orderItemIds
         └ POST /v1/payments → COMPLETED (+재고 소진)
              └ clearCart() (DELETE /v1/cart) → OrderSuccess
```

## 테스트

**백엔드 (order-service)**
- CartService/UseCase: 담기 신규 INSERT, 담기 병합(기존+신규 수량), 수량 수정, 삭제, 비움, 다른 user 격리(단위/Mockito).
- CartRepository 통합(Testcontainers): UK(user_id, sku_id) 병합, user 필터.
- CartController(MockMvc): 각 엔드포인트 상태코드 + X-User-Id 매핑.

**게이트웨이**: `/v1/cart` no-token 401 / valid-JWT 라우팅 + X-User-Id 주입.

**프론트 E2E(Playwright)**: 로그인 → 상품A 담기 → 상품B 담기(교차) → 네비바 개수 확인 → 장바구니 수량수정 → 주문하기 → 결제 → 완료 + 장바구니 비워짐(재조회 0).

## 트레이드오프 / 메모

- **가격 스냅샷**: 담을 때 unit_price 저장(담은 시점 가격). 상품 가격 변동 반영 안 함 — 데모 범위. (실서비스는 결제 직전 재검증 필요.)
- **재고 검증은 결제 시점**: 장바구니 담기는 재고를 예약하지 않음. 재고 부족은 결제(POST /v1/payments)에서 fail-closed로 드러남 — P1과 동일.
- **cart in order-service**: 장바구니를 pre-order draft로 order-service에 둠. 독립 테이블/컨트롤러라 order 생성·취소 로직과 결합 없음. 향후 규모 시 cart-service 분리 가능.
- **Checkout 무변경 재사용**: P1이 범용 lines 입력으로 설계돼 P2가 그대로 사용 — P1 설계가 P2를 준비했다.
