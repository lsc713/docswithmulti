# Mock PG 후속 개발 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mock PG 전체 구매·취소 E2E를 확보하고, 로그아웃 홈 복귀와 관리자 3단계 카테고리 생성을 완성한다.

**Architecture:** 기존 `mock-pg` 결제 포트와 `/payment/success` 콜백을 그대로 통과하는 Playwright 시나리오를 먼저 안전망으로 만든다. 로그아웃은 `App.jsx`의 성공 콜백 한 곳에서 홈 상태를 replace하고, 카테고리 생성은 기존 product-service 도메인 기능을 게이트웨이 인증 라우트와 관리자 UI에 연결한다.

**Tech Stack:** Java 21, Spring Boot 4, Spring Cloud Gateway MVC, React 19, Vite 8, Node test runner, Playwright 1.62

**Spec:** `docs/superpowers/specs/2026-08-14-mock-pg-next-work-design.md`

## Global Constraints

- 작업 브랜치는 `feat/mock-pg`, 워크트리는 `.worktrees/mock-pg`를 사용한다.
- Mock 모드는 `SPRING_PROFILES_ACTIVE=local,mock-pg`와 `VITE_PAYMENT_PROVIDER=mock`을 둘 다 명시해야 한다.
- 실제 Toss 코드 경로와 공개 `GET /v1/categories/**` 계약을 변경하지 않는다.
- 카테고리는 생성만 지원하며 수정·삭제·정렬은 추가하지 않는다.
- 모든 변경은 실패 테스트를 먼저 확인한 뒤 최소 구현한다.

---

### Task 1: Mock PG 전체 결제·취소 E2E 안전망

**Files:**
- Create: `payment-service/src/test/java/com/example/payment/infrastructure/http/MockPgProfileSelectionTest.java`
- Create: `frontend/e2e/mock-pg-flow.spec.js`
- Modify: `docs/mock-pg.md`

**Interfaces:**
- Consumes: `TossPaymentPort`, `MockTossPaymentClient`, `MockPgCancelClient`, `loginBuyer`, `openFirstInStockProductDetail`, 관리자 취소 요청 UI
- Produces: Mock 프로필 단일 결제 포트 보장과 브라우저 기반 구매→취소 승인 회귀 테스트

- [ ] **Step 1: Mock 프로필 빈 선택 실패 테스트 작성**

```java
class MockPgProfileSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("mock-pg"))
        .withUserConfiguration(MockTossPaymentClient.class, TossPaymentHttpClient.class);

    @Test
    void mockProfileSelectsOnlyMockTossPort() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TossPaymentPort.class);
            assertThat(context.getBean(TossPaymentPort.class))
                .isInstanceOf(MockTossPaymentClient.class);
        });
    }
}
```

- [ ] **Step 2: 빈 선택 테스트가 현재 설정 오류를 감지하는지 실행**

Run: `./gradlew :payment-service:test --tests '*MockPgProfileSelectionTest'`

Expected: 프로필 조건이나 의존성 선택에 문제가 있으면 FAIL, 현재 `@Profile("mock-pg")`/`@Profile("!mock-pg")`가 정확하면 PASS. 이미 구현된 프로필의 특성화 테스트이므로 production 코드는 변경하지 않는다.

- [ ] **Step 3: 전체 Mock 브라우저 흐름 테스트 작성**

`frontend/e2e/mock-pg-flow.spec.js`에 아래 골격을 구현한다. 상품 선택은 `runProductName()`과 `openFirstInStockProductDetail()`을 사용하고, 구매 버튼부터는 실제 UI를 클릭한다.

```js
test('Mock PG: 구매 → 결제완료 → 취소요청 → ADMIN 승인 → 취소완료', async ({ page, browser }) => {
  test.skip(process.env.VITE_PAYMENT_PROVIDER !== 'mock', 'Mock PG 프론트에서만 실행')
  const tossRequests = []
  page.on('request', request => {
    if (new URL(request.url()).hostname.endsWith('tosspayments.com')) tossRequests.push(request.url())
  })

  await loginBuyer(page, BUYER, BASE, GW)
  const { detail } = await openFirstInStockProductDetail(
    page,
    page.locator('.grid .card', { has: page.getByText(runProductName(), { exact: true }) }),
  )
  await detail.getByRole('button', { name: '구매하기' }).click()
  await page.getByRole('button', { name: '결제 수단 선택' }).first().click()
  await page.getByRole('button', { name: '결제하기' }).first().click()
  await expect(page).toHaveURL(/\/order-success$/)
  expect(tossRequests).toEqual([])

  await page.getByRole('button', { name: '쇼핑 계속하기' }).click()
  await page.getByRole('button', { name: '주문내역' }).click()
  const paidRow = page.locator('.history-item').first()
  const paymentKey = (await paidRow.locator('.history-key').textContent()).trim()
  page.on('dialog', dialog => dialog.accept('Mock PG 취소 E2E'))
  await paidRow.getByRole('button', { name: '취소 요청' }).click()
  await expect(paidRow.locator('.crs-badge')).toHaveText('취소 요청됨')

  const adminContext = await browser.newContext()
  const adminPage = await adminContext.newPage()
  await adminPage.goto(`${BASE}/admin/login`)
  await adminPage.getByPlaceholder('email').fill(ADMIN.email)
  await adminPage.getByPlaceholder('password').fill(ADMIN.password)
  await adminPage.getByRole('button', { name: '로그인' }).click()
  await adminPage.locator('.admin-sidebar').getByText('취소 요청', { exact: true }).click()
  const requestRow = adminPage.locator('tr', { hasText: paymentKey })
  await requestRow.getByRole('button', { name: '승인' }).click()
  await expect(requestRow).toHaveCount(0)

  await page.reload()
  await expect(page.locator('.history-item', { hasText: paymentKey }).locator('.badge'))
    .toHaveText('취소됨')
  await adminContext.close()
})
```

테스트 상단의 `BUYER`는 타임스탬프 이메일, `ADMIN.email`은 `process.env.E2E_ADMIN_EMAIL`, 비밀번호는 `password123`을 사용한다. `beforeAll`에서는 BUYER만 가입하고 관리자는 global setup이 만든 계정을 재사용한다.

- [ ] **Step 4: Mock 전체 흐름 테스트를 RED로 실행**

Mock 설정으로 서비스를 띄운다.

```bash
SPRING_PROFILES_ACTIVE=local,mock-pg ./gradlew :payment-service:bootRun
VITE_API_BASE_URL=http://localhost:8000 VITE_PAYMENT_PROVIDER=mock npm run dev
```

Run: `AUTH_COOKIE_SECURE=false VITE_PAYMENT_PROVIDER=mock E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/mock-pg-flow.spec.js`

Expected: 누락된 셀렉터, 상태 갱신 또는 Mock 경로 문제가 있으면 해당 경계에서 FAIL. 실제 Toss 요청이 한 번이라도 발생해도 FAIL.

- [ ] **Step 5: 테스트가 드러낸 Mock 흐름 결함만 최소 수정**

예상되는 정상 경로에서는 production 변경이 없어야 한다. 실패가 발생하면 로그와 네트워크 응답으로 경계를 확인하고, 그 실패를 일으킨 기존 Mock 콜백 또는 화면 갱신 한 곳만 수정한다. 테스트 기대값을 구현에 맞춰 완화하지 않는다.

- [ ] **Step 6: Mock 실행 문서에 E2E 명령 추가**

`docs/mock-pg.md`에 위 서비스 환경변수와 Playwright 명령을 그대로 추가하고, 테스트용 관리자 이메일은 user-service의 `APP_ADMIN_BOOTSTRAP_EMAILS`와 일치해야 한다고 명시한다.

- [ ] **Step 7: Task 1 검증 및 커밋**

```bash
./gradlew :payment-service:test --tests '*MockPgProfileSelectionTest'
AUTH_COOKIE_SECURE=false VITE_PAYMENT_PROVIDER=mock E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/mock-pg-flow.spec.js
git add payment-service/src/test/java/com/example/payment/infrastructure/http/MockPgProfileSelectionTest.java frontend/e2e/mock-pg-flow.spec.js docs/mock-pg.md
git commit -m "test: cover mock payment cancellation journey"
```

---

### Task 2: 로그아웃 후 상품 첫 화면 복귀

**Files:**
- Modify: `frontend/e2e/order-flow.spec.js`
- Modify: `frontend/src/App.jsx`

**Interfaces:**
- Consumes: `api.logout`, `clearOrderFlowClientState`, `openStoreView`
- Produces: 성공한 로그아웃 이후 URL `/`, 홈 view, 제거된 주문 세션

- [ ] **Step 1: 기존 로그아웃 E2E 기대값을 강화**

`logout clears owned order state...` 테스트에서 로그아웃 클릭 직후 아래를 추가한다.

```js
await expect(page).toHaveURL(/\/$/)
await expect(page.getByRole('heading', { name: /새로운 균형/ })).toBeVisible()
```

기존 실패 테스트에는 현재 URL이 `/checkout`으로 유지되는지도 추가한다.

```js
await expect(page).toHaveURL(/\/checkout$/)
```

- [ ] **Step 2: 로그아웃 성공 테스트가 실패하는지 확인**

Run: `npx playwright test e2e/order-flow.spec.js --grep 'logout clears'`

Expected: 현재 구현은 URL과 view를 홈으로 바꾸지 않으므로 `/` 기대에서 FAIL.

- [ ] **Step 3: 성공 콜백 한 곳만 수정**

`frontend/src/App.jsx`의 NavBar `onLogout`을 다음처럼 변경한다.

```jsx
onLogout={async () => {
  await api.logout()
  clearOrderFlowClientState()
  setMe(null)
  openStoreView({ name: 'home' }, true)
}}
```

API 실패 시 `await`에서 중단되므로 기존 사용자와 화면 상태는 유지된다.

- [ ] **Step 4: 로그아웃 회귀 테스트와 프론트 전체 검증**

```bash
npx playwright test e2e/order-flow.spec.js --grep 'logout'
npm run test:unit
npm run build
```

Expected: 성공·실패 로그아웃 테스트 PASS, unit 0 failures, Vite build exit 0.

- [ ] **Step 5: Task 2 커밋**

```bash
git add frontend/e2e/order-flow.spec.js frontend/src/App.jsx
git commit -m "fix: return buyers home after logout"
```

---

### Task 3: 카테고리 생성 API 라우팅과 ADMIN 권한

**Files:**
- Modify: `api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java`
- Modify: `api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java`
- Create: `product-service/src/test/java/com/example/product/presentation/controller/CategoryControllerTest.java`
- Modify: `product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java`

**Interfaces:**
- Consumes: `POST /v1/categories`, `JwtTrustHeaderFilter.H_USER_ROLE`, `CategoryService.create(Long, String)`
- Produces: 인증된 category POST 라우트와 product-service의 ADMIN 재검증

- [ ] **Step 1: 게이트웨이 RED 테스트 작성**

`GatewayRoutingIT`에 토큰 없는 요청은 401이며 downstream을 호출하지 않는 테스트와, ADMIN JWT는 `X-User-Role: ADMIN`으로 product downstream에 전달되는 테스트를 추가한다.

```java
@Test
void postCategories_noToken_returns401_downstreamNotCalled() throws Exception {
    HttpResponse<String> res = http.send(
        withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/categories")))
            .POST(HttpRequest.BodyPublishers.noBody())).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(401);
    productDownstream.verify(0, anyRequestedFor(anyUrl()));
}

@Test
void postCategories_adminJwt_routesWithRole() throws Exception {
    productDownstream.stubFor(post(urlPathEqualTo("/v1/categories"))
        .willReturn(aResponse().withStatus(200).withBody("{\"id\":1,\"level\":1}")));
    HttpResponse<String> res = http.send(
        withCsrf(HttpRequest.newBuilder(URI.create(gateway("/v1/categories")))
            .header("Authorization", "Bearer " + accessToken(42L, "ADMIN", null))
            .POST(HttpRequest.BodyPublishers.noBody())).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    productDownstream.verify(postRequestedFor(urlPathEqualTo("/v1/categories"))
        .withHeader(JwtTrustHeaderFilter.H_USER_ROLE, equalTo("ADMIN")));
}
```

- [ ] **Step 2: 게이트웨이 테스트 RED 확인**

Run: `./gradlew :api-gateway:test --tests '*GatewayRoutingIT.postCategories*'`

Expected: POST `/v1/categories`가 어떤 write predicate에도 매칭되지 않아 FAIL.

- [ ] **Step 3: 정확 경로를 기존 write 라우트에 추가**

```java
RequestPredicate write = POST("/v1/categories")
    .or(POST("/v1/products/*/images/presign"))
    .or(POST("/v1/products/*/images"))
    .or(DELETE("/v1/products/*/images/*"))
    .or(PUT("/v1/products/*/images/order"))
    .or(POST("/v1/products"));
```

- [ ] **Step 4: product-service ADMIN 권한 RED 테스트 작성**

`CategoryControllerTest`는 `ProductControllerTest`와 같은 standalone MockMvc 패턴을 사용한다.

```java
@Test
void create_returns200_forAdmin() throws Exception {
    when(categoryService.create(null, "여성"))
        .thenReturn(new CategoryService.CreateResult(1L, 1));
    mvc.perform(post("/v1/categories").header("X-User-Role", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"여성\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1));
}

@Test
void create_rejectsUserBeforeServiceCall() throws Exception {
    mvc.perform(post("/v1/categories").header("X-User-Role", "USER")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"여성\"}"))
        .andExpect(status().isForbidden());
    verify(categoryService, never()).create(any(), any());
}
```

- [ ] **Step 5: product-service 테스트 RED 확인**

Run: `./gradlew :product-service:test --tests '*CategoryControllerTest'`

Expected: USER 요청도 현재 서비스로 전달되어 403 기대가 FAIL.

- [ ] **Step 6: CategoryController에 기존 권한 패턴 적용**

```java
@PostMapping
public CategoryResponse create(
        @RequestHeader(value = "X-User-Role", required = false) String role,
        @Valid @RequestBody CreateCategoryRequest req) {
    if (!"ADMIN".equals(role)) throw new ForbiddenException();
    return CategoryResponse.from(categoryService.create(req.parentId(), req.name()));
}
```

- [ ] **Step 7: Task 3 검증 및 커밋**

```bash
./gradlew :api-gateway:test --tests '*GatewayRoutingIT.postCategories*'
./gradlew :product-service:test --tests '*CategoryControllerTest'
git add api-gateway/src/main/java/com/example/gateway/config/RouteConfig.java api-gateway/src/test/java/com/example/gateway/integration/GatewayRoutingIT.java product-service/src/main/java/com/example/product/presentation/controller/CategoryController.java product-service/src/test/java/com/example/product/presentation/controller/CategoryControllerTest.java
git commit -m "feat: secure category creation route"
```

---

### Task 4: 관리자 3단계 카테고리 생성 UI

**Files:**
- Modify: `frontend/src/api.js`
- Create: `frontend/src/admin/pages/Categories.jsx`
- Modify: `frontend/src/admin/AdminApp.jsx`
- Modify: `frontend/src/admin/AdminLayout.jsx`
- Modify: `frontend/src/admin/admin.css`
- Modify: `frontend/e2e/admin.spec.js`

**Interfaces:**
- Consumes: `api.categories()`, secured `POST /v1/categories`, `RequireRole roles={['ADMIN']}`
- Produces: `/admin/categories`, `api.createCategory`, 새 leaf가 상품 등록 select에 반영되는 관리자 여정

- [ ] **Step 1: 관리자 카테고리 E2E RED 테스트 작성**

`frontend/e2e/admin.spec.js`의 ADMIN 로그인 이후 별도 테스트에서 유일한 이름으로 대→중→소분류를 생성한다.

```js
test('ADMIN 카테고리 생성: 대분류 → 중분류 → 소분류 → 상품등록 선택지 반영', async ({ page }) => {
  await loginAdmin(page)
  const runId = Date.now()
  const root = `E2E 대분류 ${runId}`
  const middle = `E2E 중분류 ${runId}`
  const leaf = `E2E 소분류 ${runId}`

  await page.getByText('카테고리 관리', { exact: true }).click()
  await page.getByPlaceholder('카테고리 이름').fill(root)
  await page.getByRole('button', { name: '카테고리 추가' }).click()
  await expect(page.getByText(root, { exact: true })).toBeVisible()

  await page.getByLabel('상위 카테고리').selectOption({ label: root })
  await page.getByPlaceholder('카테고리 이름').fill(middle)
  await page.getByRole('button', { name: '카테고리 추가' }).click()
  await page.getByLabel('상위 카테고리').selectOption({ label: `${root} > ${middle}` })
  await page.getByPlaceholder('카테고리 이름').fill(leaf)
  await page.getByRole('button', { name: '카테고리 추가' }).click()

  await page.getByText('상품 등록', { exact: true }).click()
  await expect(page.locator('select').first().getByRole('option', { name: leaf })).toHaveCount(1)
})
```

기존 admin spec의 로그인 코드를 `loginAdmin(page)` 로컬 헬퍼로 추출해 두 관리자 테스트가 재사용한다.

- [ ] **Step 2: 카테고리 UI 테스트 RED 확인**

Run: `AUTH_COOKIE_SECURE=false E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/admin.spec.js --grep 'ADMIN 카테고리 생성'`

Expected: 사이드바 링크와 `/admin/categories` 화면이 없어 FAIL.

- [ ] **Step 3: API와 관리자 라우트 연결**

`frontend/src/api.js`:

```js
createCategory: (body) => req('/v1/categories', { method: 'POST', body, csrf: true }),
```

`AdminApp.jsx`:

```jsx
<Route path="categories" element={
  <RequireRole roles={['ADMIN']}><Categories /></RequireRole>
} />
```

`AdminLayout.jsx`의 ADMIN 메뉴에 추가한다.

```jsx
{isAdmin && <NavLink to="/admin/categories">카테고리 관리</NavLink>}
```

- [ ] **Step 4: 최소 Categories 화면 구현**

`Categories.jsx`는 `tree`, `parentId`, `name`, `busy`, `error` 상태만 가진다. 선택 가능한 상위 목록은 level 1과 2만 평탄화한다.

```jsx
const parents = tree.flatMap(root => [
  { id: root.id, label: root.name },
  ...(root.children ?? []).map(child => ({ id: child.id, label: `${root.name} > ${child.name}` })),
])

async function submit(event) {
  event.preventDefault()
  if (!name.trim() || busy) return
  setBusy(true); setError('')
  try {
    await api.createCategory({ parentId: parentId ? Number(parentId) : null, name: name.trim() })
    setName('')
    setTree(await api.categories())
  } catch (error) {
    setError(error.message)
  } finally {
    setBusy(false)
  }
}
```

렌더링은 기존 `admin-form`과 중첩 `<ul>`을 사용한다. select에는 `aria-label="상위 카테고리"`, input에는 `placeholder="카테고리 이름"`, submit에는 `카테고리 추가` 텍스트를 사용한다. 새 컴포넌트 라이브러리는 추가하지 않는다.

- [ ] **Step 5: 관리자 스타일 최소 추가**

`admin.css`에 트리 여백만 추가한다.

```css
.admin-category-tree { background: #fff; padding: 16px 24px; border: 1px solid #e5e7eb; border-radius: 10px; }
.admin-category-tree ul { margin: 6px 0; }
```

- [ ] **Step 6: 카테고리 UI 및 프론트 전체 검증**

```bash
AUTH_COOKIE_SECURE=false E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/admin.spec.js --grep 'ADMIN 카테고리 생성'
npm run test:unit
npm run build
```

Expected: E2E PASS, unit 0 failures, build exit 0.

- [ ] **Step 7: Task 4 커밋**

```bash
git add frontend/src/api.js frontend/src/admin/pages/Categories.jsx frontend/src/admin/AdminApp.jsx frontend/src/admin/AdminLayout.jsx frontend/src/admin/admin.css frontend/e2e/admin.spec.js
git commit -m "feat: add admin category creation"
```

---

### Task 5: 전체 회귀 검증과 문서 정합성

**Files:**
- Modify: `docs/mock-pg.md`
- Verify unchanged: `README.md`

**Interfaces:**
- Consumes: Tasks 1–4의 실행 명령과 사용자 흐름
- Produces: 재현 가능한 최종 검증 결과와 최신 실행 문서

- [ ] **Step 1: 문서 명령과 실제 프로필 비교**

README의 실 PG 설명은 유지하고, `docs/mock-pg.md`에 Mock 전체 E2E와 관리자 카테고리 생성 검증 명령이 존재하는지 확인한다. 실제 명령과 다른 문구만 수정하며 새 운영 문서는 만들지 않는다.

- [ ] **Step 2: 백엔드 전체 관련 모듈 테스트**

```bash
./gradlew :payment-service:test :api-gateway:test :product-service:test
```

Expected: BUILD SUCCESSFUL, 0 failed tests.

- [ ] **Step 3: 프론트 전체 검증**

```bash
cd frontend
npm run test:unit
npm run build
```

Expected: 0 failed tests, Vite build exit 0.

- [ ] **Step 4: 실제 Mock 브라우저 시나리오 검증**

```bash
AUTH_COOKIE_SECURE=false VITE_PAYMENT_PROVIDER=mock E2E_ADMIN_EMAIL=admin-e2e@example.com npx playwright test e2e/mock-pg-flow.spec.js e2e/order-flow.spec.js e2e/admin.spec.js
```

Expected: 대상 시나리오 모두 PASS, Toss 도메인 요청 0건.

- [ ] **Step 5: 작업 트리와 diff 검증**

```bash
git diff --check
git status -sb
git log --oneline --decorate -8
```

Expected: whitespace 오류 없음. 의도한 파일만 변경 또는 커밋되어 있음.

- [ ] **Step 6: 문서 수정이 있을 때만 커밋**

```bash
git add README.md docs/mock-pg.md
git commit -m "docs: update mock PG development flow"
```

문서 변경이 없으면 빈 커밋을 만들지 않는다.
