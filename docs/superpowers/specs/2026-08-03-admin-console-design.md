# 어드민 콘솔 설계 (2026-08-03)

패션 이커머스 프론트에 **어드민 전용 콘솔**을 추가한다. 어드민 로그인 → 대시보드 → 사이드바 네비게이션으로 상품/회원을 관리한다. 현재는 ADMIN으로 로그인하면 일반 상품 상세 페이지에 이미지 관리 패널이 인라인으로 끼어 나오는 방식뿐이라, 이를 독립된 어드민 경험으로 대체한다.

## 1. 범위 / 논-골

**포함**
- 어드민 전용 로그인 페이지 (role !== ADMIN 거부)
- 대시보드 홈 — 지표 카드(총 회원 수 · 카테고리 수 · 상품 총수)
- 사이드바 네비게이션 (어드민 레이아웃)
- 상품관리: 카테고리별 상품 목록 → 상품 상세 → 이미지 CRUD(등록/삭제/순서변경)
- 상품 생성: 이름 + 카테고리 + SKU 입력 폼
- 회원관리: 회원 목록 + 역할 변경

**논-골 (이번 라운드 제외)**
- 상품 수정/삭제 — 백엔드 API 없음
- 상품 전체목록 조회 API — 카테고리별 목록으로 우회
- 통계 집계 API — 기존 조회 호출로 카운트만 계산
- 취소 플로우 및 다른 서비스 — 무변경

## 2. 백엔드 변경 (딱 1개, TDD)

**신규: `GET /v1/admin/users`** (user-service)
- 회원 목록 조회, 페이지네이션 `?page=&size=` (기본 page=0, size=20)
- 응답: `{ content: [{ id, email, name, role, status, createdAt }], page, size, totalElements }`
- 비밀번호/phone 등 민감·불필요 필드는 응답에서 제외
- 인가: `/v1/admin/**`는 SecurityConfig에서 이미 `hasRole("ADMIN")`으로 게이트 → 자동 ADMIN 보호. 별도 인가 코드 불필요
- 구현: 기존 `UserQueryService` 패턴을 따르는 UseCase/Service/Repository + `AdminController`에 GET 핸들러 추가
- 취소 코어·다른 서비스 무변경

기존 `PATCH /v1/admin/users/{userId}/role`(역할 변경)은 그대로 재사용.

## 3. 프론트 구조 (Vite 멀티 엔트리 + react-router)

```
frontend/
  index.html            # 스토어프론트 (기존, 무변경)
  admin.html            # 신규 어드민 엔트리
  vite.config.js        # rollupOptions.input 에 index.html + admin.html 멀티 엔트리
  src/
    api.js              # 어드민 API 함수 추가 (같은 게이트웨이/쿠키/CSRF 재사용)
    admin/
      main.jsx          # 어드민 진입점 (createRoot → AdminApp)
      AdminApp.jsx      # BrowserRouter + 라우트 정의 + 역할 가드
      AdminLayout.jsx   # 좌측 사이드바 + <Outlet>
      admin.css
      pages/
        Login.jsx
        Dashboard.jsx
        ProductList.jsx
        ProductCreate.jsx
        ProductDetail.jsx
        Users.jsx
```

- **신규 의존성 1개**: `react-router-dom`
- **라우트**:
  - `/admin/login` — 로그인
  - `/admin` — 대시보드
  - `/admin/products` — 상품 목록(카테고리별)
  - `/admin/products/new` — 상품 생성
  - `/admin/products/:id` — 상품 상세 + 이미지 관리
  - `/admin/users` — 회원관리
- 어드민 번들은 스토어프론트와 **동일 오리진**(:5173)에서 서빙되고, 게이트웨이(:8000)를 호출한다. 세션 쿠키(httpOnly)를 스토어프론트와 공유한다.

## 4. 화면 상세

### 로그인 (`/admin/login`)
- email/password 입력 → `POST /v1/auth/login` → `GET /v1/auth/me`
- `me.role !== 'ADMIN'`이면 거부 메시지("관리자 권한이 없습니다") 표시, 진입 차단
- 성공 시 `/admin`으로 이동

### 대시보드 (`/admin`)
- 지표 카드 3개:
  - 총 회원 수 — `GET /v1/admin/users` 의 `totalElements`
  - 카테고리 수 — `GET /v1/categories` 트리에서 계산
  - 상품 총수 — leaf 카테고리별 `GET /v1/categories/{id}/products` 의 `totalElements` 합산
- 사이드바에서 상품관리 / 회원관리로 이동

### 상품관리 목록 (`/admin/products`)
- leaf 카테고리 탭 → 선택 카테고리의 상품 카드 목록 (기존 `GET /v1/categories/{id}/products` 재사용)
- 카드 클릭 → `/admin/products/:id`
- 상단에 "새 상품 등록" 버튼 → `/admin/products/new`

### 상품 상세 (`/admin/products/:id`)
- `GET /v1/products/{id}` — 갤러리 + 카테고리 경로 + SKU 테이블
- **이미지 관리 패널 항상 표시** (기존 ImageManager 로직 재사용):
  - 등록: `POST /v1/products/{id}/images/presign` → S3 PUT → `POST /v1/products/{id}/images`
  - 삭제: `DELETE /v1/products/{id}/images/{imageId}`
  - 순서변경: `PUT /v1/products/{id}/images/order`

### 상품 생성 (`/admin/products/new`)
- 입력: `name`, `categoryId`(leaf 카테고리 드롭다운), `skus[]` 반복행 `{ skuCode, optionSummary, initialStock, price }`
- `POST /v1/products` (SeedRequest) → 생성된 상품 상세(`/admin/products/:id`)로 이동해 이미지 등록 유도
- 클라이언트 검증: name 필수, categoryId 필수(leaf), SKU 1개 이상, initialStock≥0, price≥0

### 회원관리 (`/admin/users`)
- `GET /v1/admin/users` — 목록 테이블(email / name / role / status)
- 역할 셀렉트(USER ↔ ADMIN) 변경 → `PATCH /v1/admin/users/{id}/role` → 낙관적/재조회 갱신
- 페이지네이션

## 5. 인증 / 역할 가드

- 어드민 앱 로드 시 `GET /v1/auth/me` 호출 → 미인증 또는 비-ADMIN이면 `/admin/login`으로 리다이렉트
- 라우트 가드 컴포넌트가 `/admin/login`을 제외한 모든 라우트를 보호
- 세션 쿠키는 스토어프론트와 동일 오리진 공유 (httpOnly, JS 미접근)
- 변경 호출(POST/PUT/DELETE/PATCH)은 기존 `api.js`의 CSRF 토큰(`csrf_token` 쿠키 → `X-CSRF-Token` 헤더) 재사용

## 6. 테스트

**백엔드 (JUnit + MockMvc, TDD)**
- `GET /v1/admin/users`: 목록 반환 · 페이지네이션 · 빈 목록 · 비-ADMIN 403 · 민감필드 미포함

**프론트 (Playwright E2E)**
- 핵심 저니 1개: 어드민 로그인 → 대시보드 지표 → 상품 생성 → 이미지 등록 → 회원 역할변경
- 가드 케이스: USER 계정으로 `/admin` 접근 시 로그인으로 리다이렉트

## 7. 트레이드오프 메모

- **별도 엔트리**(admin.html)는 단일 앱 /admin 경로보다 설정이 무겁다. 채택 이유: 어드민 번들이 쇼핑객에게 실리지 않는다는 분리 이점. 사용자 요청.
- 상품 목록은 전역 목록 API 부재로 **카테고리별 우회** — 상품이 많아지면 "전체 상품" 뷰가 필요할 수 있으나 현재 카탈로그 규모(상품 1개)에선 불필요.
