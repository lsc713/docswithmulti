# 체크아웃 P1 (바로구매) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스토어프론트에 바로구매 종단간(상품상세 SKU 수량선택 → 주문하기 → 결제 → 완료)을 붙이고, 결제에 필요한 numeric `skuId`를 상품 상세 응답에 노출한다.

**Architecture:** 백엔드는 product-service `GET /v1/products/{id}` 응답 sku에 `skuId` 한 필드를 projection→DTO로 thread-through(취소 코어·다른 서비스 무변경). 프론트는 기존 스토어프론트(라우터 없는 `App.jsx` 뷰 상태)에 `checkout`·`success` 뷰를 추가하고, 게이트웨이 인증 라우트 `POST /v1/orders`→`POST /v1/payments`를 순차 호출(쿠키+CSRF, X-User-Id 주입)한다.

**Tech Stack:** Java 21 · Spring Boot 4 · JUnit5 + Mockito(MockMvc) · React 19 · Vite · Playwright.

## Global Constraints

- 도메인 레이어에 Spring/JPA 어노테이션 금지.
- 취소(cancel) 코어 · payment/order/user/gateway 서비스 무변경 — 이 P1은 **product-service(skuId 노출)** + **frontend(스토어프론트)** 만 건드린다.
- 어드민 콘솔 파일(`admin.html`, `src/admin/*`) 무변경. 스토어프론트 엔트리(`index.html`, `src/main.jsx`)도 무변경.
- 금액 규약(실증 확정): `itemAmount = 단가 × 수량`(라인 합계), 주문 item `price`도 라인 합계, `totalAmount = Σ itemAmount`. `quantity`는 재고 소진용(금액에 안 곱해짐).
- 프론트 변경 호출은 `api.js`의 `req(path,{csrf:true})` 재사용(쿠키+X-CSRF-Token). 신규 fetch 로직 금지.
- 이 프로젝트 프론트엔드엔 컴포넌트 단위 테스트 러너가 없다(oxlint + Playwright E2E). 프론트 태스크 검증은 dev 서버 로드 + E2E.

---

## File Structure

**백엔드 (product-service) — skuId thread-through**
- Modify: `infrastructure/persistence/ProductSkuJpaRepository.java` — 쿼리 `s.id AS skuId` + `SkuStockView.getSkuId()`
- Modify: `application/interfaces/ProductQueryRepository.java` — `SkuStock` record에 `Long skuId`
- Modify: `infrastructure/persistence/ProductQueryRepositoryImpl.java` — `v.getSkuId()` 전달
- Modify: `application/service/ProductQueryService.java` — `SkuDetail` record + 조립에 `skuId`
- Modify: `presentation/dto/ProductDetailResponse.java` — `Sku` record + `from()`에 `skuId`
- Test: `presentation/controller/ProductQueryControllerTest.java` — 상세 응답 `$.skus[0].skuId` 검증

**프론트 (frontend/src) — 스토어프론트**
- Modify: `api.js` — `createOrder`, `createPayment`
- Create: `components/Checkout.jsx`
- Create: `components/OrderSuccess.jsx`
- Modify: `components/ProductDetail.jsx` — SKU 수량 입력 + 구매하기
- Modify: `App.jsx` — `checkout`/`success` 뷰 + onBuy 배선
- Modify: `App.css` — 체크아웃/완료/수량 스타일
- Test: `e2e/checkout.spec.js`

---

## Task 1: 백엔드 — GET /v1/products/{id} 응답에 skuId 노출 (TDD)

**Files:**
- Modify: `product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java`
- Modify: `product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java`
- Modify: `product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java`
- Modify: `product-service/src/main/java/com/example/product/application/service/ProductQueryService.java`
- Modify: `product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java`
- Test: `product-service/src/test/java/com/example/product/presentation/controller/ProductQueryControllerTest.java`

**Interfaces:**
- Consumes: 기존 `ProductDetail`/`SkuDetail`/`SkuStock` 조립 체인.
- Produces: `GET /v1/products/{id}` 응답의 `skus[]` 각 항목에 `skuId`(Long) 추가. `SkuStock`/`SkuDetail`/`ProductDetailResponse.Sku` 레코드에 `skuId` 필드.

- [ ] **Step 1: 컨트롤러 실패 테스트 추가**

`ProductQueryControllerTest.java`의 `detail_...` 테스트가 mock하는 `ProductDetail`에 `SkuDetail`의 skuId를 넣고 `$.skus[0].skuId`를 검증하는 테스트를 추가한다. 기존 `detail` 테스트 스타일(standaloneSetup + `queryService`/`port` mock)을 따른다. `SkuDetail` 생성자가 곧 `skuId`를 받도록 바뀌므로, 아래 테스트는 새 시그니처를 사용한다:

```java
@Test
@DisplayName("GET /v1/products/{id} — skus[].skuId(numeric) 노출")
void detail_exposes_numeric_skuId() throws Exception {
    var detail = new ProductQueryService.ProductDetail(
            7L, "베이직 티셔츠",
            List.of(new ProductQueryService.CategoryPathNode(3, 3L, "티셔츠")),
            List.of(new ProductQueryService.SkuDetail(42L, "TS-BLK-M", "블랙/M", 10, 29000L, java.util.Map.of())),
            List.of(), List.of(), List.of());
    when(queryService.detail(7L)).thenReturn(detail);

    mvc.perform(get("/v1/products/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skus[0].skuId").value(42))
            .andExpect(jsonPath("$.skus[0].skuCode").value("TS-BLK-M"))
            .andExpect(jsonPath("$.skus[0].price").value(29000));
}
```

주의: mock 대상 메서드는 `queryService.detail(id)`(기존 테스트와 동일 — `getDetail` 아님). `ProductDetail` record 인자 순서는 `(Long id, String name, List<CategoryPathNode> category, List<SkuDetail> skus, List<ImageRef> images, List<VariantOption> variantOptions, List<VariantOption> specs)`. `SkuDetail`은 Step 5 이후 `(Long skuId, String skuCode, String optionSummary, int availableQty, long price, Map variant)`가 된다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :product-service:test --tests "*ProductQueryControllerTest"`
Expected: FAIL (컴파일 에러 — `SkuDetail`에 skuId 인자 없음).

- [ ] **Step 3: projection에 skuId 추가**

`ProductSkuJpaRepository.java` — 쿼리 SELECT에 `s.id AS skuId` 추가, 프로젝션에 getter 추가:

```java
    @Query(value = """
            SELECT s.id AS skuId, s.sku_code AS skuCode, s.option_summary AS optionSummary,
                   st.available_qty AS availableQty, s.price AS price
            FROM product_sku s
            JOIN product_stock st ON st.sku_id = s.id
            WHERE s.product_id = :productId
            ORDER BY s.id
            """, nativeQuery = true)
    List<SkuStockView> findSkuStockByProductId(@Param("productId") Long productId);

    interface SkuStockView {
        Long getSkuId();
        String getSkuCode();
        String getOptionSummary();
        int getAvailableQty();
        long getPrice();
    }
```

- [ ] **Step 4: SkuStock record + Impl 전달**

`ProductQueryRepository.java` — `SkuStock`에 skuId:
```java
    record SkuStock(Long skuId, String skuCode, String optionSummary, int availableQty, long price) {}
```
`ProductQueryRepositoryImpl.java` — `findSkuStock` 매핑:
```java
    public List<SkuStock> findSkuStock(Long productId) {
        return skuJpa.findSkuStockByProductId(productId).stream()
                .map(v -> new SkuStock(v.getSkuId(), v.getSkuCode(), v.getOptionSummary(), v.getAvailableQty(), v.getPrice()))
                .toList();
    }
```

- [ ] **Step 5: SkuDetail record + 서비스 조립**

`ProductQueryService.java` — `SkuDetail` record에 skuId(맨 앞) + 조립부 전달:
```java
    public record SkuDetail(Long skuId, String skuCode, String optionSummary, int availableQty, long price,
                            Map<String, String> variant) {}
```
조립부(기존 `findSkuStock(productId).stream().map(...)`):
```java
        List<SkuDetail> skus = queryRepository.findSkuStock(productId).stream()
                .map(s -> new SkuDetail(s.skuId(), s.skuCode(), s.optionSummary(), s.availableQty(), s.price(),
                        variantBySku.getOrDefault(s.skuCode(), Map.of())))
                .toList();
```

- [ ] **Step 6: 응답 DTO에 skuId**

`ProductDetailResponse.java` — `Sku` record + `from()`:
```java
    public record Sku(Long skuId, String skuCode, String optionSummary, int availableQty, long price,
                      Map<String, String> variant) {}
```
```java
        List<Sku> skus = d.skus().stream()
                .map(s -> new Sku(s.skuId(), s.skuCode(), s.optionSummary(), s.availableQty(), s.price(), s.variant()))
                .toList();
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :product-service:test --tests "*ProductQueryControllerTest"`
Expected: PASS

- [ ] **Step 8: 전체 product-service 테스트(회귀)**

Run: `./gradlew :product-service:test`
Expected: PASS (기존 포함 전부 — SkuStock/SkuDetail 시그니처 변경이 다른 사용처 깨지지 않는지 확인).

- [ ] **Step 9: 커밋**

```bash
git add product-service/src/main/java/com/example/product/infrastructure/persistence/ProductSkuJpaRepository.java \
        product-service/src/main/java/com/example/product/application/interfaces/ProductQueryRepository.java \
        product-service/src/main/java/com/example/product/infrastructure/persistence/ProductQueryRepositoryImpl.java \
        product-service/src/main/java/com/example/product/application/service/ProductQueryService.java \
        product-service/src/main/java/com/example/product/presentation/dto/ProductDetailResponse.java \
        product-service/src/test/java/com/example/product/presentation/controller/ProductQueryControllerTest.java
git commit -m "feat(product): 상품 상세 응답에 numeric skuId 노출 (체크아웃 결제용)"
```

---

## Task 2: 프론트 — api.js 주문·결제 함수

**Files:**
- Modify: `frontend/src/api.js`

**Interfaces:**
- Consumes: 기존 `req(path, opts)`.
- Produces:
  - `api.createOrder(body)` → `{orderId, status, items:[{orderItemId, itemName}]}`
  - `api.createPayment(body)` → `{paymentId, paymentKey, totalAmount, status, items:[...]}`

- [ ] **Step 1: api 객체에 추가**

`frontend/src/api.js`의 `export const api = { ... }` 안에:
```js
  createOrder:   (b) => req('/v1/orders',   { method: 'POST', body: b, csrf: true }),
  createPayment: (b) => req('/v1/payments', { method: 'POST', body: b, csrf: true }),
```

- [ ] **Step 2: 스모크(미인증 시 인증/CSRF 동작 확인)**

Run(스택 실행 중 가정):
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8000/v1/orders -H 'Content-Type: application/json' -d '{"items":[]}'
```
Expected: `401` 또는 `403`(미인증/CSRF). 200 아님 = 인증 경계 동작. (인증 경로 200은 Task 5 E2E에서 검증.)

- [ ] **Step 3: 커밋**
```bash
git add frontend/src/api.js
git commit -m "feat(checkout-fe): api.js createOrder/createPayment"
```

---

## Task 3: 프론트 — Checkout / OrderSuccess 컴포넌트

**Files:**
- Create: `frontend/src/components/Checkout.jsx`
- Create: `frontend/src/components/OrderSuccess.jsx`
- Modify: `frontend/src/App.css`

**Interfaces:**
- Consumes: `api.createOrder`, `api.createPayment`.
- Produces:
  - `<Checkout lines onPaid onBack />` — lines = `[{skuId, productId, itemName, optionSummary, unitPrice, quantity}]`; 결제 성공 시 `onPaid(payment)`.
  - `<OrderSuccess payment onHome />`.

- [ ] **Step 1: Checkout.jsx 작성**

```jsx
import { useState } from 'react'
import { api } from '../api'

const lineName = (l) => `${l.itemName} ${l.optionSummary ?? ''}`.trim()

export default function Checkout({ lines, onPaid, onBack }) {
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const total = lines.reduce((sum, l) => sum + l.unitPrice * l.quantity, 0)

  async function pay() {
    setErr(''); setBusy(true)
    try {
      const order = await api.createOrder({
        items: lines.map(l => ({ productId: l.productId, itemName: lineName(l), price: l.unitPrice * l.quantity })),
      })
      const payment = await api.createPayment({
        merchantId: 1, pgType: 'TOSS', cancelPeriodDays: 7,
        items: lines.map((l, i) => ({
          orderItemId: order.items[i].orderItemId,
          productId: l.productId,
          itemName: lineName(l),
          itemAmount: l.unitPrice * l.quantity,
          skuId: l.skuId,
          quantity: l.quantity,
        })),
      })
      onPaid(payment)
    } catch (e) {
      setErr(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="checkout">
      <button onClick={onBack}>뒤로</button>
      <h1>주문하기</h1>
      <table className="checkout-table">
        <thead><tr><th>상품</th><th>옵션</th><th>수량</th><th>단가</th><th>합계</th></tr></thead>
        <tbody>
          {lines.map((l, i) => (
            <tr key={i}>
              <td>{l.itemName}</td><td>{l.optionSummary}</td><td>{l.quantity}</td>
              <td>₩{l.unitPrice.toLocaleString()}</td>
              <td>₩{(l.unitPrice * l.quantity).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="checkout-total">총 결제금액 <strong>₩{total.toLocaleString()}</strong></p>
      <button className="pay-btn" onClick={pay} disabled={busy || lines.length === 0}>
        {busy ? '결제 중...' : '결제하기'}
      </button>
      {err && <p className="error">{err}</p>}
    </main>
  )
}
```

- [ ] **Step 2: OrderSuccess.jsx 작성**

```jsx
export default function OrderSuccess({ payment, onHome }) {
  return (
    <main className="order-success">
      <h1>결제 완료 🎉</h1>
      <p className="success-key">주문번호(paymentKey): <code>{payment.paymentKey}</code></p>
      <p>결제금액: <strong>₩{Number(payment.totalAmount).toLocaleString()}</strong></p>
      <p>상태: {payment.status}</p>
      <ul className="success-items">
        {payment.items?.map(it => (
          <li key={it.paymentItemId}>{it.itemName} — ₩{Number(it.itemAmount).toLocaleString()}</li>
        ))}
      </ul>
      <button onClick={onHome}>쇼핑 계속하기</button>
    </main>
  )
}
```

- [ ] **Step 3: App.css 스타일 추가**

`frontend/src/App.css` 끝에 추가:
```css
.checkout, .order-success { max-width: 640px; margin: 0 auto; padding: 16px; }
.checkout-table { width: 100%; border-collapse: collapse; margin: 12px 0; }
.checkout-table th, .checkout-table td { border-bottom: 1px solid #eee; padding: 8px; text-align: left; }
.checkout-total { text-align: right; font-size: 18px; margin: 12px 0; }
.pay-btn { width: 100%; padding: 12px; background: #4f46e5; color: #fff; border: none; border-radius: 6px; font-size: 16px; cursor: pointer; }
.pay-btn:disabled { opacity: 0.6; cursor: default; }
.order-success { text-align: center; }
.order-success .success-key code { background: #f0f0f0; padding: 2px 6px; border-radius: 4px; }
.order-success .success-items { list-style: none; padding: 0; margin: 12px 0; }
.qty-input { width: 56px; padding: 4px; }
.buy-btn { margin-top: 12px; padding: 10px 16px; background: #16a34a; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.buy-btn:disabled { opacity: 0.5; cursor: default; }
```

- [ ] **Step 4: 파싱 검증**
Run: `cd frontend && npx oxlint src/components/Checkout.jsx src/components/OrderSuccess.jsx`
Expected: no errors.

- [ ] **Step 5: 커밋**
```bash
git add frontend/src/components/Checkout.jsx frontend/src/components/OrderSuccess.jsx frontend/src/App.css
git commit -m "feat(checkout-fe): Checkout + OrderSuccess 컴포넌트"
```

---

## Task 4: 프론트 — ProductDetail 구매 UI + App 배선

**Files:**
- Modify: `frontend/src/components/ProductDetail.jsx`
- Modify: `frontend/src/App.jsx`

**Interfaces:**
- Consumes: `product.skus[].skuId`(Task 1), `<Checkout>`/`<OrderSuccess>`(Task 3).
- Produces: ProductDetail에 SKU 수량 입력 + `onBuy(lines)`; App이 `checkout`/`success` 뷰로 전환, 비로그인 시 AuthModal.

- [ ] **Step 1: ProductDetail.jsx — 수량 입력 + 구매하기**

`frontend/src/components/ProductDetail.jsx`를 아래로 교체(기존 로딩/에러/갤러리/카테고리 구조 유지 + 수량 상태·구매 버튼 추가, ADMIN ImageManager는 그대로):

```jsx
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import ImageManager from './ImageManager'

export default function ProductDetail({ id, me, onBack, onBuy }) {
  const [product, setProduct] = useState(null)
  const [error, setError] = useState(null)
  const [qty, setQty] = useState({}) // skuId -> quantity

  const load = useCallback(() => {
    api.product(id).then(p => { setProduct(p); setQty({}) }).catch(e => setError(e.message))
  }, [id])

  useEffect(() => { setProduct(null); setError(null); load() }, [load])

  if (error) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p className="error">{error}</p></main>
  if (!product) return <main className="product-detail"><button onClick={onBack}>뒤로</button><p>불러오는 중...</p></main>

  const categoryPath = product.category?.map(c => c.name).join(' > ')
  const setSkuQty = (skuId, max) => (e) => {
    const v = Math.max(0, Math.min(max, Number(e.target.value) || 0))
    setQty(q => ({ ...q, [skuId]: v }))
  }
  const lines = (product.skus ?? [])
    .filter(s => (qty[s.skuId] ?? 0) > 0)
    .map(s => ({ skuId: s.skuId, productId: product.id, itemName: product.name,
                 optionSummary: s.optionSummary, unitPrice: s.price, quantity: qty[s.skuId] }))

  return (
    <main className="product-detail">
      <button onClick={onBack}>뒤로</button>
      <div className="gallery">
        {product.images?.length
          ? product.images.map((img, i) => <img key={img.id} src={img.url} alt={`${product.name} ${i + 1}`} />)
          : <div className="gallery-ph">이미지 없음</div>}
      </div>
      {categoryPath && <p className="category-path">{categoryPath}</p>}
      <h1>{product.name}</h1>

      <table className="sku-table">
        <thead><tr><th>SKU</th><th>옵션</th><th>가격</th><th>재고</th><th>수량</th></tr></thead>
        <tbody>
          {product.skus?.map(s => (
            <tr key={s.skuId}>
              <td>{s.skuCode}</td>
              <td>{s.optionSummary}</td>
              <td>₩{s.price.toLocaleString()}</td>
              <td>{s.availableQty}</td>
              <td>
                <input className="qty-input" type="number" min="0" max={s.availableQty}
                       value={qty[s.skuId] ?? 0} onChange={setSkuQty(s.skuId, s.availableQty)} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <button className="buy-btn" disabled={lines.length === 0} onClick={() => onBuy(lines)}>구매하기</button>

      {me?.role === 'ADMIN' && (
        <ImageManager productId={id} images={product.images} onChanged={load} />
      )}
    </main>
  )
}
```

- [ ] **Step 2: App.jsx — checkout/success 뷰 + onBuy 배선**

`frontend/src/App.jsx`를 아래로 교체:

```jsx
import { useEffect, useState } from 'react'
import './App.css'
import { api } from './api'
import NavBar from './components/NavBar'
import AuthModal from './components/AuthModal'
import Home from './components/Home'
import ProductDetail from './components/ProductDetail'
import Checkout from './components/Checkout'
import OrderSuccess from './components/OrderSuccess'

export default function App() {
  const [me, setMe] = useState(null)
  const [view, setView] = useState({ name: 'home' })
  const [authOpen, setAuthOpen] = useState(false)

  useEffect(() => { api.me().then(setMe).catch(() => setMe(null)) }, [])

  function handleBuy(lines) {
    if (!me) { setAuthOpen(true); return }          // 로그인 후 재시도
    setView({ name: 'checkout', lines })
  }

  return (
    <>
      <NavBar me={me} onHome={() => setView({ name: 'home' })}
              onLoginClick={() => setAuthOpen(true)}
              onLogout={async () => { await api.logout(); setMe(null) }} />

      {view.name === 'home' && <Home onOpen={(id) => setView({ name: 'detail', id })} />}
      {view.name === 'detail' && (
        <ProductDetail id={view.id} me={me} onBack={() => setView({ name: 'home' })} onBuy={handleBuy} />
      )}
      {view.name === 'checkout' && (
        <Checkout lines={view.lines}
                  onPaid={(payment) => setView({ name: 'success', payment })}
                  onBack={() => setView({ name: 'home' })} />
      )}
      {view.name === 'success' && (
        <OrderSuccess payment={view.payment} onHome={() => setView({ name: 'home' })} />
      )}

      <AuthModal open={authOpen} onClose={() => setAuthOpen(false)}
                 onAuthed={(u) => { setMe(u); setAuthOpen(false) }} />
    </>
  )
}
```

- [ ] **Step 3: dev 서버 로드 검증**

스택(게이트웨이+백엔드+vite) 실행 중 가정. Run:
```bash
cd frontend
node -e "import('@playwright/test').then(async({chromium})=>{const b=await chromium.launch();const p=await b.newPage();await p.goto('http://localhost:5173');await p.waitForSelector('.grid .card');await p.click('.grid .card');await p.waitForSelector('.buy-btn');console.log('detail+buy 렌더 OK, skuId col:', await p.locator('.qty-input').count()>0);await b.close()})"
```
Expected: `.buy-btn`·`.qty-input` 렌더(모듈 해석 에러 없음).

- [ ] **Step 4: 커밋**
```bash
git add frontend/src/components/ProductDetail.jsx frontend/src/App.jsx
git commit -m "feat(checkout-fe): 상품상세 SKU 수량선택·구매하기 + App checkout/success 배선"
```

---

## Task 5: E2E — 바로구매 저니

**Files:**
- Create: `frontend/e2e/checkout.spec.js`

**Prerequisites:** 인프라 + user·product·order·payment·gateway + `npm run dev` 기동. product-service는 **worktree/최신(skuId 노출)** 코드로 기동. 로그인 계정 `admin@example.com`/`password123`(또는 임의 signup 계정) 사용 — 구매는 역할 무관.

**Interfaces:**
- Consumes: 실행 중 전체 스택. Playwright `test`, `expect`.
- Produces: `checkout.spec.js` — 바로구매 저니 1개.

- [ ] **Step 1: E2E 스펙 작성**

```js
import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const USER = { email: `buyer${Date.now()}@example.com`, password: 'password123', name: '구매자', phone: '010-2222-3333' }

test.beforeAll(async ({ request }) => {
  await request.post(`${GW}/v1/auth/signup`, { data: USER }).catch(() => {})
})

test('바로구매: 로그인 → 상품상세 수량선택 → 주문하기 → 결제 → 완료', async ({ page }) => {
  // 로그인 (스토어프론트 모달)
  await page.goto(BASE)
  await page.click('.navbar-right button')            // 로그인
  await page.fill('input[placeholder="email"]', USER.email)
  await page.fill('input[placeholder="password"]', USER.password)
  await page.click('.modal button[type="submit"]')
  await expect(page.locator('.navbar-right span')).toBeVisible()  // 로그인됨

  // 상품 상세 진입 + 수량 1 선택
  await page.click('.grid .card')
  await page.waitForSelector('.buy-btn')
  const firstQty = page.locator('.qty-input').first()
  await firstQty.fill('1')
  await page.click('.buy-btn')

  // 체크아웃 → 총액 표시 → 결제
  await expect(page.locator('.checkout h1')).toHaveText('주문하기')
  await expect(page.locator('.checkout-total')).toContainText('₩')
  await page.click('.pay-btn')

  // 완료 화면 (paymentKey 노출)
  await expect(page.locator('.order-success h1')).toContainText('결제 완료')
  await expect(page.locator('.success-key code')).toContainText('pay_')
})
```

- [ ] **Step 2: 실행**
Run: `cd frontend && npx playwright test e2e/checkout.spec.js`
Expected: 1 passed.
(실패 시: 테스트 셀렉터/타이밍만 조정. src/ 앱 파일·백엔드로 우회 금지. 실제 앱 버그면 보고.)

- [ ] **Step 3: 커밋**
```bash
git add frontend/e2e/checkout.spec.js
git commit -m "test(checkout-fe): 바로구매 저니 E2E"
```

---

## Self-Review 결과

- **Spec 커버리지:** skuId 노출(Task1)·api(Task2)·Checkout/Success(Task3)·ProductDetail 수량+구매·App 배선(Task4)·E2E(Task5) — 전 항목 매핑. 금액 규약(단가×수량)은 Checkout(Task3)에서 `l.unitPrice * l.quantity`로 order price·payment itemAmount 양쪽 적용.
- **타입 일관성:** `lines[{skuId,productId,itemName,optionSummary,unitPrice,quantity}]`가 ProductDetail(생성)→App(전달)→Checkout(소비) 동일. `skuId`는 백엔드 `Sku.skuId`(Long)→프론트 `s.skuId`→payment `skuId` 일치. order 응답 `items[i].orderItemId`를 index로 zip(요청 순서 = 응답 순서 가정; 단일 상품 라인이라 안전).
- **논-골 준수:** 장바구니·주문내역·조회 API 미포함. product-service + 스토어프론트만 변경. 취소 코어·어드민 무변경.
