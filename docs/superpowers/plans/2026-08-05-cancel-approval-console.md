# 취소 승인 워크플로우 P2 — 어드민/판매자 승인 큐 UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 콘솔에 취소 승인 큐 페이지를 추가한다 — ADMIN과 MERCHANT가 REQUESTED 취소 요청을 사유와 함께 보고 승인/반려한다.

**Architecture:** P1 백엔드(`/v1/cancel-requests` API)의 얇은 소비자. 백엔드는 큐 표시용 응답 필드 2개만 추가(additive). 프론트는 기존 어드민 콘솔(별도 admin.html + react-router)에 role 게이트를 일반화하고 페이지 1개를 얹는다. 실제 인가는 백엔드가 강제하고 프론트 role 가드는 UX 게이트일 뿐.

**Tech Stack:** payment-service(Java 21/Spring Boot 4) · frontend(React 19 + Vite + react-router-dom 7, oxlint, Playwright).

## Global Constraints

- **취소 코어 byte-for-byte 불변** — 백엔드 변경은 `CancelApprovalResponse`(presentation DTO) 필드 추가뿐. 서비스/도메인/취소 코어 무변경.
- **스택**: 이 브랜치는 `feat/cancel-approval-backend`(P1)에 스택. P1의 `CancelApproval`/`CancelApprovalService`/게이트웨이 라우트가 이미 존재(무변경).
- **프론트 role 가드는 UX 전용** — 진짜 인가는 게이트웨이 신뢰헤더 + `ApprovalAuthorizer`(P1). MERCHANT가 URL로 /admin/users 접근해도 백엔드가 403.
- **프론트 태스크 완료 기준**: `cd frontend && npm run lint`(oxlint) clean + `npm run build`(vite) 성공. 실제 동작은 최종 Playwright E2E로 검증.
- MERCHANT는 본인 가맹점 요청만(백엔드 `list()` 스코프). 프론트는 필터링하지 않는다.

---

## File Structure

**수정 (payment-service)**
- `presentation/dto/CancelApprovalResponse.java` — `requesterUserId`, `createdAt` 필드 추가
- `test/.../presentation/controller/CancelApprovalControllerIT.java` — 신규 필드 단언

**수정/신규 (frontend/src)**
- `api.js` — cancelRequests/approveCancel/rejectCancel 추가
- `admin/RequireRole.jsx` — 신규(RequireAdmin 일반화)
- `admin/RequireAdmin.jsx` — 제거 또는 RequireRole 래퍼로 축소
- `admin/AdminApp.jsx` — 라우트 role 게이트 + cancel-requests 라우트
- `admin/pages/Login.jsx` — ADMIN|MERCHANT 허용
- `admin/AdminLayout.jsx` — 사이드바 role 조건부
- `admin/pages/CancelRequests.jsx` — 신규 큐 페이지
- `admin/pages/Dashboard.jsx` — 대기 카운트 카드
- `e2e/cancel-approval.spec.js` — 신규 E2E

---

### Task 1: 백엔드 응답 DTO — requesterUserId + createdAt

**Files:**
- Modify: `payment-service/src/main/java/com/example/payment/presentation/dto/CancelApprovalResponse.java`
- Test: `payment-service/src/test/java/com/example/payment/presentation/controller/CancelApprovalControllerIT.java`

**Interfaces:**
- Consumes: `CancelApproval.getRequesterUserId():long`, `getCreatedAt():Instant` (P1, 이미 존재).
- Produces: `CancelApprovalResponse(long id, String paymentKey, String status, Long cancelRequestId, String reason, String decisionReason, long requesterUserId, java.time.Instant createdAt)` — 신규 2필드는 끝에 추가(기존 순서 유지).

- [ ] **Step 1: 컨트롤러 IT에 신규 필드 단언 추가(실패 유도)**

`CancelApprovalControllerIT`의 목록/요청 응답 검증 테스트에 jsonPath 단언 추가. 예(기존 request/list 테스트의 응답 검증 블록에 추가):
```java
// 요청 생성 또는 목록 응답에서:
.andExpect(jsonPath("$.requesterUserId").value(7))       // 또는 $.items[0].requesterUserId
.andExpect(jsonPath("$.createdAt").exists());
```
> 목록 응답이면 `$.items[0].requesterUserId`/`$.items[0].createdAt`. 기존 테스트가 mock한 `CancelApproval`이 requesterUserId/createdAt를 갖도록 fixture 보강(도메인 `reconstitute(...)`로 생성하거나 request()+시각 세팅). mock 기반이면 stubbed CancelApproval의 getRequesterUserId/getCreatedAt가 값을 반환하도록.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalControllerIT'`
Expected: FAIL (응답에 requesterUserId/createdAt 없음)

- [ ] **Step 3: DTO에 필드 추가**

```java
public record CancelApprovalResponse(
    long id,
    String paymentKey,
    String status,
    Long cancelRequestId,
    String reason,
    String decisionReason,
    long requesterUserId,
    java.time.Instant createdAt
) {
    public static CancelApprovalResponse of(CancelApproval a) {
        return new CancelApprovalResponse(
            a.getId(), a.getPaymentKey(), a.getStatus().name(),
            a.getCancelRequestId(), a.getReason(), a.getDecisionReason(),
            a.getRequesterUserId(), a.getCreatedAt());
    }
}
```
> `Instant`는 기존 DTO(예: `PaymentSettlementResponse`)와 동일하게 Jackson이 ISO-8601 문자열로 직렬화(추가 설정 불필요).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :payment-service:test --tests '*CancelApprovalControllerIT'`
Expected: PASS

- [ ] **Step 5: 회귀 확인**

Run: `./gradlew :payment-service:test`
Expected: 전체 PASS (취소 코어 포함 무변경 회귀).

- [ ] **Step 6: Commit**

```bash
git add payment-service/src/main/java/com/example/payment/presentation/dto/CancelApprovalResponse.java \
        payment-service/src/test/java/com/example/payment/presentation/controller/CancelApprovalControllerIT.java
git commit -m "feat(cancel-approval): 응답 DTO에 requesterUserId/createdAt 추가 (P2 큐 표시)"
```

---

### Task 2: 프론트 role 인프라 — api.js + RequireRole + AdminApp + Login

**Files:**
- Modify: `frontend/src/api.js`
- Create: `frontend/src/admin/RequireRole.jsx`
- Modify: `frontend/src/admin/RequireAdmin.jsx` (RequireRole 래퍼로 축소)
- Modify: `frontend/src/admin/AdminApp.jsx`
- Modify: `frontend/src/admin/pages/Login.jsx`

**Interfaces:**
- Consumes: 백엔드 `/v1/cancel-requests` API(P1), `api.me()`(기존).
- Produces: `api.cancelRequests(status)`, `api.approveCancel(id)`, `api.rejectCancel(id, decisionReason)`; `<RequireRole roles={[...]}>`.

- [ ] **Step 1: api.js에 함수 추가**

`export const api = { ... }` 안에 추가(중앙 `req()` 사용, 기존 스타일):
```js
  cancelRequests: (status = 'REQUESTED') => req(`/v1/cancel-requests?status=${status}`),
  approveCancel:  (id) => req(`/v1/cancel-requests/${id}/approve`, { method: 'POST', csrf: true }),
  rejectCancel:   (id, decisionReason) =>
    req(`/v1/cancel-requests/${id}/reject`, { method: 'POST', body: { decisionReason }, csrf: true }),
```

- [ ] **Step 2: RequireRole 컴포넌트 생성**

```jsx
// frontend/src/admin/RequireRole.jsx
import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api } from '../api'

export default function RequireRole({ roles, children }) {
  const [state, setState] = useState({ loading: true, ok: false })
  useEffect(() => {
    api.me()
      .then(me => setState({ loading: false, ok: roles.includes(me.role) }))
      .catch(() => setState({ loading: false, ok: false }))
  }, [])  // roles는 렌더마다 새 배열일 수 있으나 마운트 1회 판정으로 충분
  if (state.loading) return <div className="admin-main">확인 중...</div>
  if (!state.ok) return <Navigate to="/admin/login" replace />
  return children
}
```

- [ ] **Step 3: RequireAdmin 축소**

```jsx
// frontend/src/admin/RequireAdmin.jsx
import RequireRole from './RequireRole'
export default function RequireAdmin({ children }) {
  return <RequireRole roles={['ADMIN']}>{children}</RequireRole>
}
```

- [ ] **Step 4: AdminApp 라우팅 수정**

```jsx
// AdminApp.jsx — 레이아웃은 ADMIN+MERCHANT, ADMIN 전용 라우트는 개별 게이트
import RequireRole from './RequireRole'
import CancelRequests from './pages/CancelRequests'
// ...
<Route path="/admin" element={<RequireRole roles={['ADMIN','MERCHANT']}><AdminLayout /></RequireRole>}>
  <Route index element={<Dashboard />} />
  <Route path="cancel-requests" element={<CancelRequests />} />
  <Route path="products" element={<RequireRole roles={['ADMIN']}><ProductList /></RequireRole>} />
  <Route path="products/new" element={<RequireRole roles={['ADMIN']}><ProductCreate /></RequireRole>} />
  <Route path="products/:id" element={<RequireRole roles={['ADMIN']}><ProductDetail /></RequireRole>} />
  <Route path="users" element={<RequireRole roles={['ADMIN']}><Users /></RequireRole>} />
</Route>
```
> `CancelRequests`/`Dashboard`(index)는 레이아웃 게이트(ADMIN+MERCHANT)로 접근. Task 3에서 `CancelRequests`를 생성하므로, 이 태스크에서는 import가 깨지지 않게 최소 스텁(빈 컴포넌트 `export default function CancelRequests(){return null}`)을 먼저 두거나, Task 3와 병합 실행 시 순서 보장. **여기서는 최소 스텁 파일을 생성**해 빌드가 통과하게 한다(Task 3가 내용 채움).

- [ ] **Step 5: Login role 완화**

`Login.jsx`의 `submit`에서:
```js
const me = await api.me()
if (!['ADMIN', 'MERCHANT'].includes(me.role)) { setErr('관리자/판매자 권한이 없습니다.'); return }
navigate('/admin', { replace: true })
```

- [ ] **Step 6: 스텁 CancelRequests 생성 (빌드 통과용)**

```jsx
// frontend/src/admin/pages/CancelRequests.jsx  (Task 3에서 채움)
export default function CancelRequests() { return <div className="admin-main">취소 요청</div> }
```

- [ ] **Step 7: lint + build 통과 확인**

Run: `cd frontend && npm run lint && npm run build`
Expected: oxlint clean, vite build 성공.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/api.js frontend/src/admin/RequireRole.jsx frontend/src/admin/RequireAdmin.jsx \
        frontend/src/admin/AdminApp.jsx frontend/src/admin/pages/Login.jsx frontend/src/admin/pages/CancelRequests.jsx
git commit -m "feat(cancel-approval): 콘솔 role 게이트 일반화(RequireRole) + api + cancel-requests 라우트"
```

---

### Task 3: 프론트 UI — 사이드바 조건부 + CancelRequests 페이지 + 대시보드 카드

**Files:**
- Modify: `frontend/src/admin/AdminLayout.jsx`
- Modify: `frontend/src/admin/pages/CancelRequests.jsx` (스텁 → 실제)
- Modify: `frontend/src/admin/pages/Dashboard.jsx`

**Interfaces:**
- Consumes: `api.me()`, `api.cancelRequests('REQUESTED')`, `api.approveCancel(id)`, `api.rejectCancel(id, reason)` (Task 2).
- 응답 행 형태: `{ id, paymentKey, status, cancelRequestId, reason, decisionReason, requesterUserId, createdAt }` (Task 1). 목록 응답은 `{ items: [...] }`.

- [ ] **Step 1: AdminLayout 사이드바 role 조건부**

```jsx
// AdminLayout.jsx
import { useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api } from '../api'

export default function AdminLayout() {
  const navigate = useNavigate()
  const [role, setRole] = useState(null)
  useEffect(() => { api.me().then(me => setRole(me.role)).catch(() => setRole(null)) }, [])
  async function logout() {
    try { await api.logout() } catch { /* 무시 */ }
    navigate('/admin/login', { replace: true })
  }
  const isAdmin = role === 'ADMIN'
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="brand">어드민 콘솔</div>
        {isAdmin && <NavLink to="/admin" end>대시보드</NavLink>}
        <NavLink to="/admin/cancel-requests">취소 요청</NavLink>
        {isAdmin && <NavLink to="/admin/products">상품관리</NavLink>}
        {isAdmin && <NavLink to="/admin/products/new">상품 등록</NavLink>}
        {isAdmin && <NavLink to="/admin/users">회원관리</NavLink>}
        <button onClick={logout} className="logout-btn">로그아웃</button>
      </aside>
      <main className="admin-main"><Outlet /></main>
    </div>
  )
}
```
> MERCHANT는 '취소 요청'만 보인다. (대시보드 index 라우트는 남아있으나 MERCHANT 사이드바엔 링크 미노출 — 직접 URL 접근 시 대시보드는 카운트 카드만 보이며 무해.)

- [ ] **Step 2: CancelRequests 페이지 구현**

```jsx
// frontend/src/admin/pages/CancelRequests.jsx
import { useEffect, useState } from 'react'
import { api } from '../../api'

export default function CancelRequests() {
  const [items, setItems] = useState([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)

  function load() {
    setLoading(true)
    api.cancelRequests('REQUESTED')
      .then(r => { setItems(r.items ?? []); setErr('') })
      .catch(e => setErr(e.message))
      .finally(() => setLoading(false))
  }
  useEffect(load, [])

  async function approve(id) {
    try { await api.approveCancel(id); load() } catch (e) { setErr(e.message) }
  }
  async function reject(id) {
    const reason = window.prompt('반려 사유를 입력하세요')
    if (!reason) return
    try { await api.rejectCancel(id, reason); load() } catch (e) { setErr(e.message) }
  }

  if (loading) return <div className="admin-main">불러오는 중...</div>
  return (
    <div>
      <h1>취소 요청</h1>
      {err && <p className="error">{err}</p>}
      {items.length === 0 ? (
        <p>대기 중인 취소 요청이 없습니다.</p>
      ) : (
        <table className="admin-table">
          <thead><tr><th>결제키</th><th>요청자</th><th>사유</th><th>요청시각</th><th>액션</th></tr></thead>
          <tbody>
            {items.map(it => (
              <tr key={it.id}>
                <td>{it.paymentKey}</td>
                <td>{it.requesterUserId}</td>
                <td>{it.reason}</td>
                <td>{new Date(it.createdAt).toLocaleString()}</td>
                <td>
                  <button onClick={() => approve(it.id)}>승인</button>
                  <button onClick={() => reject(it.id)}>반려</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
```
> 클래스명(`admin-table` 등)은 기존 admin.css의 테이블 스타일을 재사용. 없으면 기존 페이지(Users.jsx)의 목록 마크업/클래스를 따른다.

- [ ] **Step 3: Dashboard 대기 카운트 카드**

`Dashboard.jsx`에 마운트 시 `api.cancelRequests('REQUESTED')` 개수로 카드 추가(기존 지표 카드 마크업/클래스 재사용):
```jsx
const [pending, setPending] = useState(null)
useEffect(() => { api.cancelRequests('REQUESTED').then(r => setPending((r.items ?? []).length)).catch(() => setPending(null)) }, [])
// 렌더: <div className="stat-card"><span>대기 중 취소요청</span><strong>{pending ?? '-'}</strong></div>
```
> 기존 Dashboard의 카드 컨테이너/클래스에 맞춰 삽입. 값 로드 실패 시 '-'.

- [ ] **Step 4: lint + build 통과 확인**

Run: `cd frontend && npm run lint && npm run build`
Expected: oxlint clean, vite build 성공.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/admin/AdminLayout.jsx frontend/src/admin/pages/CancelRequests.jsx frontend/src/admin/pages/Dashboard.jsx
git commit -m "feat(cancel-approval): 승인 큐 페이지 + 사이드바 role 조건부 + 대시보드 카운트"
```

---

### Task 4: Playwright E2E

**Files:**
- Create: `frontend/e2e/cancel-approval.spec.js`

**Interfaces:**
- Consumes: 실 스택(게이트웨이 8000 + 서비스들) 또는 기존 e2e가 사용하는 baseURL/헬퍼. 기존 `e2e/history.spec.js`·`admin.spec.js`의 로그인/결제생성/헬퍼 패턴 재사용.

- [ ] **Step 1: ADMIN 승인/반려 저니 작성**

기존 `e2e/checkout.spec.js`/`history.spec.js`의 헬퍼로 fixture를 API로 구성:
```
1. 구매자 로그인 → 상품 조회 → 주문 생성 → 결제 생성(COMPLETED) — 기존 헬퍼 재사용
2. 구매자가 취소 요청 제출: POST /v1/cancel-requests? → P2엔 프론트 요청 UI가 없으므로(그건 P3),
   이 테스트는 API 직접 호출로 REQUESTED 1건 생성(request.post, 쿠키/CSRF 포함).
3. ADMIN 로그인(콘솔) → /admin/cancel-requests → 방금 요청이 목록에 보임(결제키·사유) →
   '승인' 클릭 → 목록에서 사라짐(재조회 0 또는 해당 행 없음).
4. (반려 변형) 새 요청 → '반려' → prompt 사유 → 사라짐.
```
> 취소 요청 생성은 P2에 프론트 UI가 없으므로 `request.post('/v1/cancel-requests...')` 또는 `page.request`로 직접. CSRF/쿠키는 기존 스펙의 로그인 헬퍼가 세팅한 컨텍스트를 사용.

- [ ] **Step 2: MERCHANT 사이드바/스코프 검증(route 인터셉트)**

실 MERCHANT 유저 시드가 없으므로 `page.route`로 `**/v1/auth/me`를 MERCHANT role로, `**/v1/cancel-requests*`를 fixture 목록으로 스텁하여 프론트 role 로직만 검증:
```
- page.route('**/v1/auth/me', → { role:'MERCHANT', ... })
- /admin/cancel-requests 진입 → 사이드바에 '취소 요청'만, '상품관리'/'회원관리' 링크 없음(assert not visible)
- 목록 렌더 확인
```
> 이는 프론트 role-conditional UX 게이트 검증. 실제 가맹점 스코프 인가는 백엔드 P1 테스트가 이미 증명.

- [ ] **Step 3: USER 리다이렉트 검증**

USER(일반 구매자) 로그인 상태로 `/admin/cancel-requests` 접근 → `/admin/login`으로 리다이렉트(RequireRole).

- [ ] **Step 4: E2E 실행**

Run: `cd frontend && npx playwright test cancel-approval` (실 스택 기동 전제 — 기존 e2e와 동일 조건)
Expected: 통과. 실 스택 미기동이면 스킵/문서화(기존 e2e와 동일 제약).
> 실 스택 기동이 불가한 CI 환경이면, 최소한 스펙이 문법/셀렉터상 유효함을 확인하고, 라이브 검증은 로컬 스택에서 수행(기존 프론트 E2E와 동일 관행).

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/cancel-approval.spec.js
git commit -m "test(cancel-approval): 승인 큐 E2E — ADMIN 승인/반려 + MERCHANT 사이드바 + USER 리다이렉트"
```

---

## Self-Review 체크

- **Spec 커버리지**: DTO 필드(T1) · role 게이트 일반화+Login+api(T2) · 사이드바/큐/대시보드(T3) · E2E(T4) — 스펙 전 항목 태스크 존재.
- **취소 코어 불변**: 백엔드 변경은 `CancelApprovalResponse` 필드 2개 추가뿐(T1 Step5 전체 회귀). 서비스/도메인/취소 코어 무변경.
- **타입 일관성**: `api.cancelRequests/approveCancel/rejectCancel` 시그니처가 T2 정의 = T3 사용 일치. 응답 필드(`requesterUserId`,`createdAt`,`items`)가 T1 DTO = T3 렌더 일치.
- **빌드 안전**: T2가 CancelRequests 스텁을 먼저 만들어 AdminApp import가 깨지지 않음(T3가 내용 채움).
- **프론트 가드=UX only**: 실제 인가는 백엔드(문서화). MERCHANT E2E는 route 인터셉트로 프론트 로직만 검증.
```
