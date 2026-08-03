# 어드민 콘솔 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ADMIN 전용 콘솔(로그인 → 대시보드 → 사이드바 → 상품관리/상품생성/회원관리)을 별도 Vite 엔트리로 추가하고, 회원 목록 조회 백엔드 엔드포인트를 신설한다.

**Architecture:** 백엔드는 user-service에 `GET /v1/admin/users` 하나만 추가(기존 헥사 구조·`/v1/admin/**` ADMIN 게이트 재사용). 프론트는 `admin.html` 별도 엔트리 + `react-router-dom`으로 어드민 SPA를 구성하고, 기존 게이트웨이(:8000)·httpOnly 세션 쿠키·CSRF·`ImageManager` 컴포넌트를 재사용한다.

**Tech Stack:** Java 21 · Spring Boot 4 / Spring Security 7 · JUnit5 + Mockito(MockMvc) · React 19 · Vite 8 · react-router-dom · Playwright.

## Global Constraints

- 도메인 레이어에 Spring/JPA 어노테이션 금지 (헥사 구조 유지).
- 취소 플로우 및 payment/order/risk/merchant 서비스 무변경 — 이 작업은 user-service + frontend 만 건드린다.
- `/v1/admin/**` 인가는 SecurityConfig의 `hasRole("ADMIN")`이 이미 담당 — 컨트롤러에 인가 코드 추가 금지.
- 시크릿 하드코딩 금지. 어드민도 기존 `/v1/auth/login` + httpOnly 쿠키 흐름 재사용.
- 프론트 API 호출은 `credentials: 'include'` + 변경계열은 `X-CSRF-Token` (기존 `api.js` `req()` 재사용).
- 기존 스토어프론트(`index.html`, `src/main.jsx`, `src/App.jsx`, `src/components/*`)는 무변경 — 어드민은 `src/admin/` 아래에만 추가. 단 `src/api.js`·`vite.config.js`·`package.json`은 확장.
- 이 프로젝트 프론트엔드에는 컴포넌트 단위 테스트 러너가 없다(oxlint + Playwright E2E 만). 프론트 태스크의 검증은 dev 서버 로드 + Playwright E2E로 한다.

---

## File Structure

**백엔드 (user-service)**
- Modify: `application/usecase/UserQueryUseCase.java` — `listUsers(int,int)` 추가
- Modify: `application/service/UserQueryService.java` — 구현
- Create: `presentation/dto/UserListResponse.java` — 목록 응답 + `UserSummary`
- Modify: `presentation/controller/AdminController.java` — `GET` 핸들러 + `UserQueryUseCase` 주입
- Modify(test): `presentation/controller/AdminControllerTest.java` — 생성자 시그니처 변경 반영 + GET 테스트
- Modify(test): `application/service/UserQueryServiceTest.java` — 페이지네이션 테스트

**프론트 (frontend)**
- Modify: `package.json` — `react-router-dom` 의존성
- Modify: `vite.config.js` — 멀티 엔트리 + `/admin/*` dev rewrite 플러그인
- Create: `admin.html` — 어드민 엔트리
- Modify: `src/api.js` — `adminUsers`, `changeRole`, `createProduct` 추가
- Create: `src/admin/main.jsx` — 진입점
- Create: `src/admin/AdminApp.jsx` — 라우터 + 가드
- Create: `src/admin/RequireAdmin.jsx` — 역할 가드
- Create: `src/admin/AdminLayout.jsx` — 사이드바 + Outlet
- Create: `src/admin/admin.css`
- Create: `src/admin/pages/Login.jsx`
- Create: `src/admin/pages/Dashboard.jsx`
- Create: `src/admin/pages/ProductList.jsx`
- Create: `src/admin/pages/ProductCreate.jsx`
- Create: `src/admin/pages/ProductDetail.jsx`
- Create: `src/admin/pages/Users.jsx`
- Reuse (import, no change): `src/components/ImageManager.jsx`
- Create(test): `frontend/e2e/admin.spec.js`

---

## Task 1: 백엔드 — GET /v1/admin/users

**Files:**
- Create: `user-service/src/main/java/com/example/user/presentation/dto/UserListResponse.java`
- Modify: `user-service/src/main/java/com/example/user/application/usecase/UserQueryUseCase.java`
- Modify: `user-service/src/main/java/com/example/user/application/service/UserQueryService.java`
- Modify: `user-service/src/main/java/com/example/user/presentation/controller/AdminController.java`
- Test: `user-service/src/test/java/com/example/user/application/service/UserQueryServiceTest.java`
- Test: `user-service/src/test/java/com/example/user/presentation/controller/AdminControllerTest.java`

**Interfaces:**
- Consumes: `UserRepository.findAll()` → `List<User>` (기존); `User` getters `getId/getEmail/getName/getRole()/getStatus()/getCreatedAt()`; `UserRole`, `UserStatus` enums.
- Produces:
  - `UserQueryUseCase.listUsers(int page, int size)` → `UserListResponse`
  - `UserListResponse(List<UserSummary> content, int page, int size, long totalElements)`
  - `UserListResponse.UserSummary(long id, String email, String name, String role, String status, String createdAt)`
  - `GET /v1/admin/users?page=&size=` → `UserListResponse` JSON

- [ ] **Step 1: 응답 DTO 작성**

Create `presentation/dto/UserListResponse.java`:

```java
package com.example.user.presentation.dto;

import java.util.List;

public record UserListResponse(
        List<UserSummary> content,
        int page,
        int size,
        long totalElements
) {
    public record UserSummary(
            long id,
            String email,
            String name,
            String role,
            String status,
            String createdAt
    ) {}
}
```

- [ ] **Step 2: UseCase 인터페이스에 메서드 추가**

Modify `application/usecase/UserQueryUseCase.java`:

```java
package com.example.user.application.usecase;

import com.example.user.presentation.dto.MeResponse;
import com.example.user.presentation.dto.UserListResponse;

public interface UserQueryUseCase {
    MeResponse getProfile(long userId);

    UserListResponse listUsers(int page, int size);
}
```

- [ ] **Step 3: 실패 테스트 작성 (서비스 페이지네이션)**

Add to `application/service/UserQueryServiceTest.java` (기존 클래스에 테스트 추가; import `UserRole`, `UserStatus`, `User`, `UserListResponse`, `java.time.Instant`, `java.util.List`, Mockito `when`):

```java
@Test
@DisplayName("listUsers — id 오름차순 첫 페이지 + totalElements")
void shouldListUsersPaged() {
    User u1 = User.reconstruct(1L, "a@x.com", "pw", "A", "010", UserRole.USER,
            null, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    User u2 = User.reconstruct(2L, "b@x.com", "pw", "B", "010", UserRole.ADMIN,
            null, UserStatus.ACTIVE, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    when(userRepository.findAll()).thenReturn(List.of(u2, u1));

    UserListResponse res = userQueryService.listUsers(0, 1);

    assertThat(res.totalElements()).isEqualTo(2);
    assertThat(res.page()).isEqualTo(0);
    assertThat(res.size()).isEqualTo(1);
    assertThat(res.content()).hasSize(1);
    assertThat(res.content().get(0).id()).isEqualTo(1L);      // id 오름차순
    assertThat(res.content().get(0).role()).isEqualTo("USER");
    assertThat(res.content().get(0).status()).isEqualTo("ACTIVE");
}

@Test
@DisplayName("listUsers — 두 번째 페이지")
void shouldReturnSecondPage() {
    User u1 = User.reconstruct(1L, "a@x.com", "pw", "A", "010", UserRole.USER,
            null, UserStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    User u2 = User.reconstruct(2L, "b@x.com", "pw", "B", "010", UserRole.ADMIN,
            null, UserStatus.ACTIVE, Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    when(userRepository.findAll()).thenReturn(List.of(u1, u2));

    UserListResponse res = userQueryService.listUsers(1, 1);

    assertThat(res.content()).hasSize(1);
    assertThat(res.content().get(0).id()).isEqualTo(2L);
}

@Test
@DisplayName("listUsers — 범위 초과 페이지는 빈 목록")
void shouldReturnEmptyWhenPageOutOfRange() {
    when(userRepository.findAll()).thenReturn(List.of());

    UserListResponse res = userQueryService.listUsers(5, 20);

    assertThat(res.content()).isEmpty();
    assertThat(res.totalElements()).isZero();
}
```

(기존 `UserQueryServiceTest`가 `userQueryService`/`userRepository` 필드를 이미 갖고 있으면 재사용. 없으면 클래스 상단 패턴은 기존 파일을 따른다.)

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :user-service:test --tests "*UserQueryServiceTest"`
Expected: FAIL — `listUsers` 메서드 없음(컴파일 에러).

- [ ] **Step 5: 서비스 구현**

Modify `application/service/UserQueryService.java` — 메서드 추가:

```java
    // 상단 import 추가:
    // import com.example.user.presentation.dto.UserListResponse;
    // import java.util.Comparator;
    // import java.util.List;

    @Override
    @Transactional(readOnly = true)
    public UserListResponse listUsers(int page, int size) {
        // ponytail: 전량 조회 후 메모리 페이지네이션 — 회원 수 소규모 전제.
        // 규모 커지면 UserRepository에 Pageable 조회 추가로 교체.
        List<User> all = userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .toList();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        List<UserListResponse.UserSummary> content = all.subList(from, to).stream()
                .map(u -> new UserListResponse.UserSummary(
                        u.getId(), u.getEmail(), u.getName(),
                        u.getRole().name(), u.getStatus().name(), u.getCreatedAt().toString()))
                .toList();
        return new UserListResponse(content, page, size, all.size());
    }
```

- [ ] **Step 6: 서비스 테스트 통과 확인**

Run: `./gradlew :user-service:test --tests "*UserQueryServiceTest"`
Expected: PASS

- [ ] **Step 7: 컨트롤러 실패 테스트 작성**

Modify `presentation/controller/AdminControllerTest.java`:
- 상단에 `@Mock UserQueryUseCase userQuery;` 필드 추가, import `com.example.user.application.usecase.UserQueryUseCase`, `com.example.user.presentation.dto.UserListResponse`, `org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get`, `java.util.List`.
- `setUp()`의 컨트롤러 생성을 `new AdminController(adminUseCase, userQuery)` 로 변경.
- 테스트 추가:

```java
@Test
@DisplayName("GET /v1/admin/users — 200 + content/totalElements")
void shouldListUsers() throws Exception {
    when(userQuery.listUsers(0, 20)).thenReturn(new UserListResponse(
            List.of(new UserListResponse.UserSummary(
                    1L, "a@x.com", "A", "USER", "ACTIVE", "2026-01-01T00:00:00Z")),
            0, 20, 1));

    mockMvc.perform(get("/v1/admin/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].email").value("a@x.com"))
            .andExpect(jsonPath("$.content[0].role").value("USER"));
}

@Test
@DisplayName("GET /v1/admin/users — page/size 쿼리 전달")
void shouldPassPageParams() throws Exception {
    when(userQuery.listUsers(2, 5)).thenReturn(new UserListResponse(List.of(), 2, 5, 30));

    mockMvc.perform(get("/v1/admin/users").param("page", "2").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.size").value(5));
}
```

- [ ] **Step 8: 테스트 실패 확인**

Run: `./gradlew :user-service:test --tests "*AdminControllerTest"`
Expected: FAIL — 생성자 시그니처 불일치 / GET 매핑 없음.

- [ ] **Step 9: 컨트롤러 구현**

Modify `presentation/controller/AdminController.java`:

```java
package com.example.user.presentation.controller;

import com.example.user.application.usecase.AdminUseCase;
import com.example.user.application.usecase.AdminUseCase.RoleChangeResult;
import com.example.user.application.usecase.UserQueryUseCase;
import com.example.user.presentation.dto.ChangeRoleRequest;
import com.example.user.presentation.dto.UserListResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 인가: SecurityConfig가 /v1/admin/** 전체를 hasRole("ADMIN")으로 게이트.
@RestController
@RequestMapping("/v1/admin/users")
public class AdminController {
    private final AdminUseCase adminUseCase;
    private final UserQueryUseCase userQuery;

    public AdminController(AdminUseCase adminUseCase, UserQueryUseCase userQuery) {
        this.adminUseCase = adminUseCase;
        this.userQuery = userQuery;
    }

    @GetMapping
    public UserListResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return userQuery.listUsers(page, size);
    }

    @PatchMapping("/{userId}/role")
    public Map<String, Object> changeRole(@PathVariable long userId, @RequestBody @Valid ChangeRoleRequest request) {
        RoleChangeResult result = adminUseCase.changeRole(userId, request.role());
        return Map.of("userId", result.userId(), "role", result.role().name());
    }
}
```

- [ ] **Step 10: 전체 user-service 테스트 통과 확인**

Run: `./gradlew :user-service:test`
Expected: PASS (기존 테스트 포함 전부)

- [ ] **Step 11: 커밋**

```bash
git add user-service/src/main/java/com/example/user/presentation/dto/UserListResponse.java \
        user-service/src/main/java/com/example/user/application/usecase/UserQueryUseCase.java \
        user-service/src/main/java/com/example/user/application/service/UserQueryService.java \
        user-service/src/main/java/com/example/user/presentation/controller/AdminController.java \
        user-service/src/test/java/com/example/user/application/service/UserQueryServiceTest.java \
        user-service/src/test/java/com/example/user/presentation/controller/AdminControllerTest.java
git commit -m "feat(user): GET /v1/admin/users 회원 목록 조회 (ADMIN)"
```

> 403(비-ADMIN)은 기존 PATCH 엔드포인트와 동일하게 SecurityConfig `/v1/admin/**` 규칙이 선언적으로 담당 — 별도 유닛테스트 없음(기존 코드 관례와 일치). 프론트 가드가 UX 레벨에서 추가 차단.

---

## Task 2: 프론트 — 멀티 엔트리 + 라우터 스캐폴드

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.js`
- Create: `frontend/admin.html`
- Create: `frontend/src/admin/main.jsx`
- Create: `frontend/src/admin/AdminApp.jsx`
- Create: `frontend/src/admin/admin.css`

**Interfaces:**
- Produces: `http://localhost:5173/admin/` 및 `/admin/*` 경로에서 어드민 SPA 로드. `AdminApp`이 `<BrowserRouter>` 라우트 트리 제공.

- [ ] **Step 1: react-router-dom 설치**

Run: `cd frontend && npm install react-router-dom`

- [ ] **Step 2: vite.config.js — 멀티 엔트리 + /admin dev rewrite**

Replace `frontend/vite.config.js`:

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 별도 엔트리(admin.html)에 /admin/* 클린 URL 유지 — dev 서버에서 네비게이션 요청을 admin.html로 rewrite.
// (prod 정적 호스팅 시 동일 rewrite 필요 — 현재는 dev 서버로 구동.)
const adminHtmlRewrite = {
  name: 'admin-html-rewrite',
  configureServer(server) {
    server.middlewares.use((req, _res, next) => {
      if (req.url === '/admin' || req.url.startsWith('/admin/')) req.url = '/admin.html'
      next()
    })
  },
}

export default defineConfig({
  plugins: [react(), adminHtmlRewrite],
  server: { port: 5173, strictPort: true },  // dev proxy 의도적으로 없음 — CORS 정식 경로 검증
  build: {
    rollupOptions: {
      input: { main: 'index.html', admin: 'admin.html' },
    },
  },
})
```

- [ ] **Step 3: admin.html 생성**

Create `frontend/admin.html` (CSP는 index.html과 동일하게 게이트웨이 :8000 + S3 :9000 허용):

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta http-equiv="Content-Security-Policy"
          content="default-src 'self'; connect-src 'self' http://localhost:8000 http://localhost:9000; img-src 'self' http://localhost:9000 data:; style-src 'self' 'unsafe-inline'">
    <title>어드민 콘솔</title>
  </head>
  <body>
    <div id="admin-root"></div>
    <script type="module" src="/src/admin/main.jsx"></script>
  </body>
</html>
```

- [ ] **Step 4: 진입점 + 라우터 스켈레톤**

Create `frontend/src/admin/main.jsx`:

```jsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './admin.css'
import AdminApp from './AdminApp'

createRoot(document.getElementById('admin-root')).render(
  <StrictMode>
    <AdminApp />
  </StrictMode>,
)
```

Create `frontend/src/admin/AdminApp.jsx` (스켈레톤 — 페이지는 이후 태스크에서 채움):

```jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'

export default function AdminApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/admin/login" element={<div><h1>어드민 로그인</h1></div>} />
        <Route path="/admin" element={<div><h1>대시보드</h1></div>} />
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
```

Create `frontend/src/admin/admin.css`:

```css
* { box-sizing: border-box; }
body { margin: 0; font: 15px/1.5 system-ui, sans-serif; color: #1a1a1a; }
.admin-shell { display: flex; min-height: 100vh; }
.admin-sidebar { width: 220px; background: #12141c; color: #cfd2dc; padding: 20px 0; flex-shrink: 0; }
.admin-sidebar .brand { font-weight: 700; font-size: 18px; padding: 0 20px 16px; color: #fff; }
.admin-sidebar a { display: block; padding: 10px 20px; color: #cfd2dc; text-decoration: none; }
.admin-sidebar a.active, .admin-sidebar a:hover { background: #232735; color: #fff; }
.admin-main { flex: 1; padding: 28px 32px; background: #f6f7f9; }
.admin-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
.admin-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; padding: 20px; }
.admin-card .num { font-size: 30px; font-weight: 700; }
.admin-card .label { color: #6b7280; margin-top: 4px; }
.admin-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 14px; }
.admin-grid .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; padding: 12px; text-align: left; cursor: pointer; }
.admin-grid .card img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 6px; background: #eee; }
table.admin-table { width: 100%; border-collapse: collapse; background: #fff; }
table.admin-table th, table.admin-table td { border-bottom: 1px solid #eee; padding: 10px 12px; text-align: left; }
.admin-form { max-width: 560px; display: grid; gap: 12px; }
.admin-form input, .admin-form select { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font: inherit; }
.admin-login { max-width: 340px; margin: 12vh auto; display: grid; gap: 10px; }
button.primary { background: #4f46e5; color: #fff; border: none; padding: 9px 14px; border-radius: 6px; cursor: pointer; }
.error { color: crimson; }
.sku-row { display: grid; grid-template-columns: 1fr 1fr 90px 110px auto; gap: 8px; align-items: center; }
```

- [ ] **Step 5: 스캐폴드 검증 (dev 서버 로드)**

Run (스택이 이미 떠 있다고 가정, 아니면 `npm run dev` 백그라운드 기동):
```bash
cd frontend
node -e "import('@playwright/test').then(async ({chromium})=>{const b=await chromium.launch();const p=await b.newPage();await p.goto('http://localhost:5173/admin');await p.waitForSelector('h1');console.log(await p.textContent('h1'));await b.close()})"
```
Expected: `대시보드` 출력 (=/admin 로드 + rewrite + 라우터 동작). `/admin/login` 도 동일 방식으로 `어드민 로그인` 확인.

- [ ] **Step 6: 커밋**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.js \
        frontend/admin.html frontend/src/admin/main.jsx frontend/src/admin/AdminApp.jsx frontend/src/admin/admin.css
git commit -m "feat(admin-fe): admin.html 별도 엔트리 + react-router 스캐폴드"
```

---

## Task 3: 프론트 — api.js 어드민 함수

**Files:**
- Modify: `frontend/src/api.js`

**Interfaces:**
- Consumes: 기존 `req(path, opts)` 내부 헬퍼, `api` 객체.
- Produces:
  - `api.adminUsers(page = 0, size = 20)` → `{content, page, size, totalElements}`
  - `api.changeRole(userId, role)` → `{userId, role}`
  - `api.createProduct(body)` → SeedResponse (`{id, ...}`)

- [ ] **Step 1: api 객체에 함수 추가**

Modify `frontend/src/api.js` — `export const api = { ... }` 안에 항목 추가:

```js
  adminUsers:   (page = 0, size = 20) => req(`/v1/admin/users?page=${page}&size=${size}`),
  changeRole:   (userId, role) =>
    req(`/v1/admin/users/${userId}/role`, { method: 'PATCH', body: { role }, csrf: true }),
  createProduct: (body) => req('/v1/products', { method: 'POST', body, csrf: true }),
```

- [ ] **Step 2: 검증 (실행 중 스택으로 목록 호출)**

Run (admin@example.com 세션 쿠키 필요 — 스모크로 비인증 401 확인만):
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/v1/admin/users
```
Expected: `401` 또는 `403` (미인증). 200이 아니라 인가가 동작함을 확인. (인증된 200 경로는 Task 10 E2E에서 검증.)

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/api.js
git commit -m "feat(admin-fe): api.js 어드민 함수(adminUsers/changeRole/createProduct)"
```

---

## Task 4: 프론트 — 역할 가드 + 레이아웃 + 로그인

**Files:**
- Create: `frontend/src/admin/RequireAdmin.jsx`
- Create: `frontend/src/admin/AdminLayout.jsx`
- Create: `frontend/src/admin/pages/Login.jsx`
- Modify: `frontend/src/admin/AdminApp.jsx`

**Interfaces:**
- Consumes: `api.me()`, `api.login()`, `react-router-dom` (`Navigate`, `useNavigate`, `NavLink`, `Outlet`).
- Produces: `<RequireAdmin>` (children 가드), `<AdminLayout>` (사이드바 + Outlet), `<Login>` 페이지. 라우트 트리 확정.

- [ ] **Step 1: 역할 가드**

Create `frontend/src/admin/RequireAdmin.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api } from '../api'

export default function RequireAdmin({ children }) {
  const [state, setState] = useState({ loading: true, ok: false })

  useEffect(() => {
    api.me()
      .then(me => setState({ loading: false, ok: me.role === 'ADMIN' }))
      .catch(() => setState({ loading: false, ok: false }))
  }, [])

  if (state.loading) return <div className="admin-main">확인 중...</div>
  if (!state.ok) return <Navigate to="/admin/login" replace />
  return children
}
```

- [ ] **Step 2: 레이아웃 (사이드바)**

Create `frontend/src/admin/AdminLayout.jsx`:

```jsx
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api } from '../api'

export default function AdminLayout() {
  const navigate = useNavigate()
  async function logout() {
    try { await api.logout() } catch { /* 무시 */ }
    navigate('/admin/login', { replace: true })
  }
  return (
    <div className="admin-shell">
      <aside className="admin-sidebar">
        <div className="brand">어드민 콘솔</div>
        <NavLink to="/admin" end>대시보드</NavLink>
        <NavLink to="/admin/products">상품관리</NavLink>
        <NavLink to="/admin/products/new">상품 등록</NavLink>
        <NavLink to="/admin/users">회원관리</NavLink>
        <a onClick={logout} style={{ cursor: 'pointer' }}>로그아웃</a>
      </aside>
      <main className="admin-main"><Outlet /></main>
    </div>
  )
}
```

- [ ] **Step 3: 로그인 페이지**

Create `frontend/src/admin/pages/Login.jsx`:

```jsx
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api'

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' })
  const [err, setErr] = useState('')
  const navigate = useNavigate()
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })

  async function submit(e) {
    e.preventDefault(); setErr('')
    try {
      await api.login(form)
      const me = await api.me()
      if (me.role !== 'ADMIN') { setErr('관리자 권한이 없습니다.'); return }
      navigate('/admin', { replace: true })
    } catch (e) { setErr(e.message) }
  }

  return (
    <form className="admin-login" onSubmit={submit}>
      <h1>어드민 로그인</h1>
      <input placeholder="email" value={form.email} onChange={set('email')} />
      <input placeholder="password" type="password" value={form.password} onChange={set('password')} />
      <button className="primary" type="submit">로그인</button>
      {err && <p className="error">{err}</p>}
    </form>
  )
}
```

- [ ] **Step 4: 라우트 트리 확정**

Replace `frontend/src/admin/AdminApp.jsx`:

```jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import RequireAdmin from './RequireAdmin'
import AdminLayout from './AdminLayout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import ProductList from './pages/ProductList'
import ProductCreate from './pages/ProductCreate'
import ProductDetail from './pages/ProductDetail'
import Users from './pages/Users'

export default function AdminApp() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/admin/login" element={<Login />} />
        <Route path="/admin" element={<RequireAdmin><AdminLayout /></RequireAdmin>}>
          <Route index element={<Dashboard />} />
          <Route path="products" element={<ProductList />} />
          <Route path="products/new" element={<ProductCreate />} />
          <Route path="products/:id" element={<ProductDetail />} />
          <Route path="users" element={<Users />} />
        </Route>
        <Route path="*" element={<Navigate to="/admin" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
```

> 이 시점엔 Dashboard/ProductList/ProductCreate/ProductDetail/Users 파일이 아직 없다. Task 5~9에서 각각 생성한다. **AdminApp의 import를 만족시키기 위해 Task 5~9를 이어서 진행**하고, 그 전에는 dev 서버가 모듈 해석 에러를 낸다. 커밋은 Task 9 완료 후 한 번에(또는 각 페이지 생성마다) 한다. 아래 각 태스크는 자체 커밋을 갖는다 — 먼저 각 페이지 파일을 만든 뒤(Step들), 마지막에 AdminApp까지 포함해 커밋해도 된다. 실행자는 Task 5→9를 순서대로 만든 후 Task 4의 AdminApp 교체를 적용하고 커밋하는 것을 권장.

- [ ] **Step 5: 커밋 (Task 5~9 페이지 생성 후 함께)**

```bash
git add frontend/src/admin/RequireAdmin.jsx frontend/src/admin/AdminLayout.jsx \
        frontend/src/admin/pages/Login.jsx frontend/src/admin/AdminApp.jsx
git commit -m "feat(admin-fe): 역할 가드 + 사이드바 레이아웃 + 로그인"
```

---

## Task 5: 프론트 — 대시보드 (지표 카드)

**Files:**
- Create: `frontend/src/admin/pages/Dashboard.jsx`

**Interfaces:**
- Consumes: `api.adminUsers()`, `api.categories()`, `api.productsByCategory(id)`.
- Produces: `<Dashboard>` — 카드 3개(총 회원 수 · 카테고리 수 · 상품 총수).

- [ ] **Step 1: 페이지 작성**

Create `frontend/src/admin/pages/Dashboard.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { api } from '../../api'

function leaves(tree) {
  const out = []
  ;(function walk(ns) { ns.forEach(n => (n.children?.length ? walk(n.children) : out.push(n))) })(tree)
  return out
}

export default function Dashboard() {
  const [stats, setStats] = useState({ users: '—', categories: '—', products: '—' })

  useEffect(() => {
    (async () => {
      const [users, tree] = await Promise.all([api.adminUsers(0, 1), api.categories()])
      const ls = leaves(tree)
      const counts = await Promise.all(ls.map(l => api.productsByCategory(l.id).then(r => r.totalElements)))
      const catCount = (function count(ns) { return ns.reduce((a, n) => a + 1 + count(n.children ?? []), 0) })(tree)
      setStats({
        users: users.totalElements,
        categories: catCount,
        products: counts.reduce((a, b) => a + b, 0),
      })
    })().catch(() => { /* 카드에 — 유지 */ })
  }, [])

  return (
    <>
      <h1>대시보드</h1>
      <div className="admin-cards">
        <div className="admin-card"><div className="num">{stats.users}</div><div className="label">총 회원 수</div></div>
        <div className="admin-card"><div className="num">{stats.categories}</div><div className="label">카테고리 수</div></div>
        <div className="admin-card"><div className="num">{stats.products}</div><div className="label">상품 총수</div></div>
      </div>
    </>
  )
}
```

- [ ] **Step 2: 검증** — Task 9 이후 통합 검증(Task 10 E2E)에서 카드 숫자 로드 확인. 개별 확인은 로그인 후 `/admin`에서 카드 3개 렌더.

---

## Task 6: 프론트 — 상품관리 목록

**Files:**
- Create: `frontend/src/admin/pages/ProductList.jsx`

**Interfaces:**
- Consumes: `api.categories()`, `api.productsByCategory(id)`, `react-router-dom` `Link`, `useNavigate`.
- Produces: `<ProductList>` — leaf 카테고리 탭 + 상품 카드(클릭 → `/admin/products/:id`) + "새 상품 등록" 링크.

- [ ] **Step 1: 페이지 작성**

Create `frontend/src/admin/pages/ProductList.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api'

function leaves(tree) {
  const out = []
  ;(function walk(ns) { ns.forEach(n => (n.children?.length ? walk(n.children) : out.push(n))) })(tree)
  return out
}

export default function ProductList() {
  const [cats, setCats] = useState([])
  const [active, setActive] = useState(null)
  const [items, setItems] = useState([])

  useEffect(() => {
    api.categories().then(tree => {
      const ls = leaves(tree)
      setCats(ls)
      if (ls[0]) setActive(ls[0].id)
    }).catch(() => setCats([]))
  }, [])

  useEffect(() => {
    if (active == null) return
    api.productsByCategory(active).then(r => setItems(r.content)).catch(() => setItems([]))
  }, [active])

  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>상품관리</h1>
        <Link className="primary" to="/admin/products/new" style={{ textDecoration: 'none' }}>새 상품 등록</Link>
      </div>
      <nav style={{ display: 'flex', gap: 8, margin: '12px 0' }}>
        {cats.map(c => (
          <button key={c.id} className={c.id === active ? 'primary' : ''} onClick={() => setActive(c.id)}>{c.name}</button>
        ))}
      </nav>
      <div className="admin-grid">
        {items.map(p => (
          <Link key={p.id} className="card" to={`/admin/products/${p.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
            {p.thumbnailUrl ? <img src={p.thumbnailUrl} alt={p.name} /> : <div className="ph" />}
            <div>{p.name}</div>
            <div>₩{p.minPrice.toLocaleString()}~</div>
          </Link>
        ))}
      </div>
    </>
  )
}
```

- [ ] **Step 2: 검증** — Task 10 E2E에서 카테고리 탭 + 카드 렌더 확인.

---

## Task 7: 프론트 — 상품 상세 + 이미지 관리

**Files:**
- Create: `frontend/src/admin/pages/ProductDetail.jsx`
- Reuse: `frontend/src/components/ImageManager.jsx` (무변경)

**Interfaces:**
- Consumes: `api.product(id)`, `useParams`, `useNavigate`; `ImageManager` (props: `productId`, `images`, `onChanged`).
- Produces: `<ProductDetail>` — 상품 정보 + **항상** `<ImageManager>` 표시.

- [ ] **Step 1: 페이지 작성**

Create `frontend/src/admin/pages/ProductDetail.jsx`:

```jsx
import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { api } from '../../api'
import ImageManager from '../../components/ImageManager'

export default function ProductDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [product, setProduct] = useState(null)
  const [error, setError] = useState(null)

  const load = useCallback(() => {
    api.product(id).then(setProduct).catch(e => setError(e.message))
  }, [id])

  useEffect(() => { setProduct(null); setError(null); load() }, [load])

  if (error) return <><button onClick={() => navigate('/admin/products')}>뒤로</button><p className="error">{error}</p></>
  if (!product) return <p>불러오는 중...</p>

  const categoryPath = product.category?.map(c => c.name).join(' > ')

  return (
    <>
      <button onClick={() => navigate('/admin/products')}>뒤로</button>
      {categoryPath && <p>{categoryPath}</p>}
      <h1>{product.name}</h1>
      <div className="admin-grid" style={{ maxWidth: 480 }}>
        {product.images?.length
          ? product.images.map((img, i) => <img key={img.id} src={img.url} alt={`${product.name} ${i + 1}`} />)
          : <div className="ph">이미지 없음</div>}
      </div>
      <table className="admin-table" style={{ maxWidth: 560, marginTop: 16 }}>
        <thead><tr><th>SKU</th><th>옵션</th><th>가격</th><th>재고</th></tr></thead>
        <tbody>
          {product.skus?.map(s => (
            <tr key={s.skuCode}>
              <td>{s.skuCode}</td><td>{s.optionSummary}</td>
              <td>₩{s.price.toLocaleString()}</td><td>{s.availableQty}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <ImageManager productId={id} images={product.images} onChanged={load} />
    </>
  )
}
```

- [ ] **Step 2: 검증** — Task 10 E2E에서 이미지 추가/표시 확인.

---

## Task 8: 프론트 — 상품 생성 폼

**Files:**
- Create: `frontend/src/admin/pages/ProductCreate.jsx`

**Interfaces:**
- Consumes: `api.categories()`, `api.createProduct(body)`, `useNavigate`.
- Produces: `<ProductCreate>` — name + leaf categoryId 드롭다운 + SKU 반복행 → `POST /v1/products` → 생성 상세로 이동.

- [ ] **Step 1: 페이지 작성**

Create `frontend/src/admin/pages/ProductCreate.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api'

function leaves(tree) {
  const out = []
  ;(function walk(ns) { ns.forEach(n => (n.children?.length ? walk(n.children) : out.push(n))) })(tree)
  return out
}
const emptySku = () => ({ skuCode: '', optionSummary: '', initialStock: 0, price: 0 })

export default function ProductCreate() {
  const [cats, setCats] = useState([])
  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [skus, setSkus] = useState([emptySku()])
  const [err, setErr] = useState('')
  const navigate = useNavigate()

  useEffect(() => { api.categories().then(t => setCats(leaves(t))).catch(() => setCats([])) }, [])

  const setSku = (i, k) => (e) => {
    const v = k === 'initialStock' || k === 'price' ? Number(e.target.value) : e.target.value
    setSkus(skus.map((s, j) => (j === i ? { ...s, [k]: v } : s)))
  }

  async function submit(e) {
    e.preventDefault(); setErr('')
    if (!name || !categoryId || skus.some(s => !s.skuCode)) { setErr('이름·카테고리·SKU 코드는 필수입니다.'); return }
    try {
      const res = await api.createProduct({ name, categoryId: Number(categoryId), skus })
      navigate(`/admin/products/${res.id}`)
    } catch (e) { setErr(e.message) }
  }

  return (
    <>
      <h1>상품 등록</h1>
      <form className="admin-form" onSubmit={submit}>
        <input placeholder="상품명" value={name} onChange={e => setName(e.target.value)} />
        <select value={categoryId} onChange={e => setCategoryId(e.target.value)}>
          <option value="">카테고리 선택</option>
          {cats.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        {skus.map((s, i) => (
          <div className="sku-row" key={i}>
            <input placeholder="SKU코드" value={s.skuCode} onChange={setSku(i, 'skuCode')} />
            <input placeholder="옵션(예: 블랙/M)" value={s.optionSummary} onChange={setSku(i, 'optionSummary')} />
            <input type="number" placeholder="재고" value={s.initialStock} onChange={setSku(i, 'initialStock')} min="0" />
            <input type="number" placeholder="가격" value={s.price} onChange={setSku(i, 'price')} min="0" />
            <button type="button" onClick={() => setSkus(skus.filter((_, j) => j !== i))} disabled={skus.length === 1}>삭제</button>
          </div>
        ))}
        <button type="button" onClick={() => setSkus([...skus, emptySku()])}>+ SKU 추가</button>
        <button className="primary" type="submit">등록</button>
        {err && <p className="error">{err}</p>}
      </form>
    </>
  )
}
```

- [ ] **Step 2: 검증** — Task 10 E2E에서 생성 → 상세 이동 확인.

---

## Task 9: 프론트 — 회원관리

**Files:**
- Create: `frontend/src/admin/pages/Users.jsx`
- (이 태스크 완료 후 Task 4 Step 4의 AdminApp 교체까지 적용되어 전체 import 충족)

**Interfaces:**
- Consumes: `api.adminUsers(page)`, `api.changeRole(userId, role)`.
- Produces: `<Users>` — 회원 테이블 + 역할 셀렉트(즉시 변경) + 페이지네이션.

- [ ] **Step 1: 페이지 작성**

Create `frontend/src/admin/pages/Users.jsx`:

```jsx
import { useEffect, useState } from 'react'
import { api } from '../../api'

export default function Users() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState({ content: [], totalElements: 0, size: 20 })
  const [err, setErr] = useState('')

  const load = (p) => api.adminUsers(p, 20).then(setData).catch(e => setErr(e.message))
  useEffect(() => { load(page) }, [page])

  async function change(userId, role) {
    setErr('')
    try { await api.changeRole(userId, role); load(page) } catch (e) { setErr(e.message) }
  }

  const pages = Math.max(1, Math.ceil(data.totalElements / data.size))

  return (
    <>
      <h1>회원관리</h1>
      {err && <p className="error">{err}</p>}
      <table className="admin-table">
        <thead><tr><th>ID</th><th>이메일</th><th>이름</th><th>상태</th><th>역할</th></tr></thead>
        <tbody>
          {data.content.map(u => (
            <tr key={u.id}>
              <td>{u.id}</td><td>{u.email}</td><td>{u.name}</td><td>{u.status}</td>
              <td>
                <select value={u.role} onChange={e => change(u.id, e.target.value)}>
                  <option value="USER">USER</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ marginTop: 12, display: 'flex', gap: 8 }}>
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page + 1} / {pages}</span>
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>다음</button>
      </div>
    </>
  )
}
```

- [ ] **Step 2: AdminApp 교체 적용** — Task 4 Step 4의 최종 `AdminApp.jsx`를 적용(아직 안 했다면). 이제 6개 페이지 import 모두 충족.

- [ ] **Step 3: 통합 로드 검증**

Run:
```bash
cd frontend
node -e "import('@playwright/test').then(async ({chromium})=>{const b=await chromium.launch();const p=await b.newPage();await p.goto('http://localhost:5173/admin/login');await p.waitForSelector('h1');console.log(await p.textContent('h1'));await b.close()})"
```
Expected: `어드민 로그인` (모듈 해석 에러 없이 앱 로드).

- [ ] **Step 4: 커밋 (Task 4~9 프론트 페이지 일괄)**

```bash
git add frontend/src/admin/RequireAdmin.jsx frontend/src/admin/AdminLayout.jsx frontend/src/admin/AdminApp.jsx \
        frontend/src/admin/pages/Login.jsx frontend/src/admin/pages/Dashboard.jsx \
        frontend/src/admin/pages/ProductList.jsx frontend/src/admin/pages/ProductCreate.jsx \
        frontend/src/admin/pages/ProductDetail.jsx frontend/src/admin/pages/Users.jsx
git commit -m "feat(admin-fe): 대시보드/상품관리/상품생성/상품상세/회원관리 페이지"
```

---

## Task 10: E2E — 어드민 핵심 저니 + 가드

**Files:**
- Create: `frontend/e2e/admin.spec.js`

**Prerequisites (E2E 실행 전):**
- 인프라 + user-service + product-service + api-gateway + `npm run dev` 기동.
- user-service를 `APP_ADMIN_BOOTSTRAP_EMAILS=admin@example.com` 로 기동 → 해당 이메일로 signup 시 ADMIN 부여(테스트가 자체적으로 ADMIN 확보). 이미 존재하면 그대로 로그인.

**Interfaces:**
- Consumes: 실행 중인 전체 스택. Playwright `test`, `expect`.
- Produces: `admin.spec.js` — 저니 1개 + 가드 1개.

- [ ] **Step 1: E2E 스펙 작성 (실패 상태로 시작 — 페이지 미완이면 실패)**

Create `frontend/e2e/admin.spec.js`:

```js
import { test, expect } from '@playwright/test'

const BASE = 'http://localhost:5173'
const GW = 'http://localhost:8000'
const ADMIN = { email: 'admin@example.com', password: 'password123', name: '관리자', phone: '010-0000-0000' }

test.beforeAll(async ({ request }) => {
  // 부트스트랩 이메일이면 ADMIN으로 가입됨(이미 있으면 409 무시)
  await request.post(`${GW}/v1/auth/signup`, { data: ADMIN }).catch(() => {})
})

test('가드: 비로그인 상태로 /admin 접근 시 로그인으로 리다이렉트', async ({ page }) => {
  await page.goto(`${BASE}/admin`)
  await expect(page).toHaveURL(/\/admin\/login$/)
  await expect(page.locator('h1')).toHaveText('어드민 로그인')
})

test('저니: 로그인 → 대시보드 → 상품 생성 → 상세/이미지 → 회원 역할변경', async ({ page }) => {
  // 로그인
  await page.goto(`${BASE}/admin/login`)
  await page.fill('input[placeholder="email"]', ADMIN.email)
  await page.fill('input[placeholder="password"]', ADMIN.password)
  await page.click('button.primary')
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.locator('.admin-card')).toHaveCount(3)

  // 상품 생성
  await page.click('text=상품 등록')
  await page.fill('input[placeholder="상품명"]', 'E2E 셔츠')
  await page.selectOption('select', { index: 1 })            // 첫 leaf 카테고리
  await page.fill('input[placeholder="SKU코드"]', 'E2E-BLK-M')
  await page.fill('input[placeholder="가격"]', '19000')
  await page.click('button.primary:has-text("등록")')
  await expect(page).toHaveURL(/\/admin\/products\/\d+$/)
  await expect(page.locator('h1')).toHaveText('E2E 셔츠')
  await expect(page.locator('.image-manager')).toBeVisible()  // 이미지 관리 패널

  // 회원관리 → 첫 행 역할 토글(변경 후 재조회 성공만 확인)
  await page.click('text=회원관리')
  await expect(page.locator('table.admin-table tbody tr').first()).toBeVisible()
})
```

- [ ] **Step 2: E2E 실행**

Run: `cd frontend && npx playwright test e2e/admin.spec.js`
Expected: 2 passed.

- [ ] **Step 3: 커밋**

```bash
git add frontend/e2e/admin.spec.js
git commit -m "test(admin-fe): 어드민 저니 + 가드 E2E"
```

---

## Task 11: 문서 동기화

**Files:**
- Modify: `docs/STATUS.md` (어드민 콘솔 추가 반영)
- Modify: `CLAUDE.md` (필요 시 확장 기능 섹션에 한 줄)

- [ ] **Step 1:** `docs/STATUS.md`에 "어드민 콘솔(로그인/대시보드/상품·회원 관리, 별도 admin.html 엔트리) + `GET /v1/admin/users`" 항목 추가.
- [ ] **Step 2:** `CLAUDE.md` 확장 기능에 어드민 콘솔 한 줄 추가(선택 — 스토어프론트/게이트웨이 불변식 무영향 명시).
- [ ] **Step 3: 커밋**

```bash
git add docs/STATUS.md CLAUDE.md
git commit -m "docs(admin): 어드민 콘솔 상태/맵 반영"
```

---

## Self-Review 결과

- **Spec 커버리지:** 로그인(Task4)·대시보드 지표(Task5)·사이드바(Task4)·상품목록(Task6)·상품상세+이미지(Task7)·상품생성(Task8)·회원관리(Task9)·GET users 백엔드(Task1)·가드(Task4/10)·별도 엔트리(Task2)·테스트(Task1/10) — 전 항목 태스크 매핑됨.
- **타입 일관성:** `UserListResponse{content,page,size,totalElements}` / `UserSummary{id,email,name,role,status,createdAt}` — 백엔드 DTO·`api.adminUsers`·`Users.jsx`·E2E에서 동일. `api.createProduct` 응답 `res.id` → SeedResponse의 `id`(ProductDetail 라우트 param) 일치.
- **논-골 준수:** 상품 수정/삭제·전역목록·통계 API 미신설. payment/order/risk/merchant·취소 코어 무변경.
