# 취소 승인 워크플로우 P2 — 어드민/판매자 승인 큐 UI 설계 (2026-08-05)

취소 승인 워크플로우의 **P2**. P1(백엔드 승인 코어, PR #99)이 노출한 `/v1/cancel-requests` API 위에 **승인 큐 UI**를 얹는다. ADMIN과 MERCHANT가 어드민 콘솔에서 취소 요청과 사유를 보고 승인/반려한다.

**스택**: 이 브랜치(`feat/cancel-approval-console`)는 `feat/cancel-approval-backend`(P1)에 **스택**한다 — 프론트가 P1의 엔드포인트를 호출하므로. P1(#99) 머지 후 base를 main으로 리타겟하는 것이 이상적(스택 PR base `--delete-branch` 주의, #96 사고 교훈).

## 결정 사항 (brainstorming)

- **UI 위치: 기존 어드민 콘솔**(별도 admin.html + react-router). 신규 페이지 1개 추가.
- **접근: ADMIN + MERCHANT**. ADMIN은 전체 요청, MERCHANT는 본인 가맹점 요청만(백엔드 `list()`가 이미 스코프). 콘솔의 상품/회원 관리는 **ADMIN 전용 유지**.

## 범위 / 논-골

**P2 포함**
- 백엔드: 승인 응답 DTO에 큐 표시용 필드 추가(`requesterUserId`, `createdAt`) — P1에서 이월된 M-1.
- 콘솔 role 게이트 일반화: `RequireAdmin` → `RequireRole(roles)`. 취소요청 페이지는 ADMIN+MERCHANT, 상품/회원은 ADMIN 전용.
- 어드민 Login이 MERCHANT도 허용(현재 ADMIN 하드체크 완화).
- 사이드바 role 조건부: MERCHANT는 '취소 요청'만, ADMIN은 전부.
- 승인 큐 페이지: REQUESTED 목록(사유·요청자·결제·시각) + 승인/반려(반려는 사유 입력).
- 대시보드에 '대기 중 취소요청 N' 지표 카드(가벼움).

**P2 논-골**
- 스토어프론트 USER 요청 전환(즉시취소 → 요청 제출) — P3.
- 부분취소 승인 UI, 자동승인 규칙.
- 별도 판매자 포털(콘솔 공유로 갈음).
- 승인 이력/감사 화면(REQUESTED 큐만).

## 백엔드 델타 (payment-service, P1 위에)

**`CancelApprovalResponse`에 필드 2개 추가** (P1 이월 M-1):
- `requesterUserId`(long), `createdAt`(Instant) — 둘 다 이미 `CancelApproval` 도메인에 존재. `of(CancelApproval)` 매퍼에 매핑만 추가. 기존 필드(`id, paymentKey, status, cancelRequestId, reason, decisionReason`) 유지.
- 이 변경은 순수 additive(필드 추가). 취소 코어·서비스 로직 무변경. 기존 컨트롤러 IT의 응답 단언에 필드 확인 추가.
- `paymentStatus`(approve 응답)는 **논-골** — 큐는 REQUESTED만 보고, 승인 후엔 목록에서 사라지므로 프론트가 재조회로 갱신. 불필요.

## 프론트엔드 (어드민 콘솔, `frontend/src/admin/`)

**`RequireRole.jsx`(신규, `RequireAdmin` 대체)**
```
export default function RequireRole({ roles, children }) {
  // api.me() → me.role 이 roles 에 포함되면 통과, 아니면 /admin/login 리다이렉트
}
```
- `RequireAdmin`은 제거하거나 `RequireRole roles={['ADMIN']}` 래퍼로 남긴다.

**`AdminApp.jsx`(라우팅 수정)**
- 레이아웃 게이트: `<RequireRole roles={['ADMIN','MERCHANT']}><AdminLayout/></RequireRole>`.
- ADMIN 전용 라우트(products, products/new, products/:id, users)는 각 element를 `<RequireRole roles={['ADMIN']}>...</RequireRole>`로 감싼다.
- 신규 라우트 `cancel-requests` → `<CancelRequests/>`(레이아웃 게이트로 ADMIN+MERCHANT 접근).
- index(Dashboard)는 ADMIN+MERCHANT 공용(대시보드는 role별 내용 분기).

**`AdminLayout.jsx`(사이드바 role 조건부)**
- 마운트 시 `api.me()`로 role 로드. `<NavLink to="/admin/cancel-requests">취소 요청</NavLink>`는 ADMIN+MERCHANT 노출. 상품관리/상품등록/회원관리/대시보드 링크는 ADMIN만.
- MERCHANT는 사이드바에 '취소 요청'(+ 필요시 대시보드)만 보인다.

**`pages/Login.jsx`(role 완화)**
- `if (me.role !== 'ADMIN')` → `if (!['ADMIN','MERCHANT'].includes(me.role))`. 에러 문구 '관리자/판매자 권한이 없습니다.'

**`pages/CancelRequests.jsx`(신규)**
- 마운트 시 `api.cancelRequests('REQUESTED')` → 목록. 각 행: 결제키, 요청자(userId), 사유, 요청시각(createdAt).
- 각 행 액션: **승인**(`api.approveCancel(id)`) / **반려**(사유 `window.prompt` → `api.rejectCancel(id, reason)`). 성공 시 목록 재조회(해당 행이 REQUESTED에서 빠짐).
- 실패(403/409/404) 시 에러 메시지 표시(예: 이미 처리됨 409).
- 빈 목록이면 '대기 중인 취소 요청이 없습니다'.

**`pages/Dashboard.jsx`(지표 카드 추가)**
- `api.cancelRequests('REQUESTED')`의 개수로 '대기 중 취소요청 N' 카드. MERCHANT는 본인 가맹점 기준(백엔드 스코프).

**`api.js`(함수 추가)** — 중앙 `req()` 사용:
- `cancelRequests: (status='REQUESTED') => req('/v1/cancel-requests?status=' + status)`
- `approveCancel: (id) => req('/v1/cancel-requests/' + id + '/approve', { method:'POST', csrf:true })`
- `rejectCancel: (id, decisionReason) => req('/v1/cancel-requests/' + id + '/reject', { method:'POST', body:{ decisionReason }, csrf:true })`

## 인증 / 게이트웨이 / role 흐름

- 게이트웨이 `/v1/cancel-requests/**` 인증 라우트는 **P1에서 이미 존재**(무변경). 콘솔은 쿠키 인증(v2.0) → 게이트웨이가 JWT 검증 후 `X-User-Role`/`X-User-Id`/`X-Merchant-Id` 주입.
- MERCHANT 로그인 → role=MERCHANT + merchantId 클레임 → 게이트웨이가 `X-Merchant-Id` 주입 → 백엔드 `list()`가 본인 가맹점 요청만 반환, `approve/reject`는 본인 가맹점만 인가(P1 `ApprovalAuthorizer`).
- 변경계열(approve/reject POST) → CSRF 토큰(`csrf:true`).
- **전제 데이터**: MERCHANT role 사용자 + 그 가맹점 결제가 있어야 E2E 검증 가능(시드/부트스트랩). 없으면 ADMIN 경로만 라이브 검증.

## 데이터 흐름

```
어드민 콘솔 로그인(ADMIN|MERCHANT) → RequireRole 통과
  사이드바 '취소 요청' → CancelRequests
    GET /v1/cancel-requests?status=REQUESTED  (MERCHANT는 본인 가맹점만)
      행: 결제키·요청자·사유·시각
        ├ 승인 → POST /{id}/approve → (백엔드가 기존 cancel() 실행) → 목록 재조회
        └ 반려 → 사유 → POST /{id}/reject → 목록 재조회
```

## 배포 게이트 (P1과 동일, 변화 없음)

- payment ingress NetworkPolicy(게이트웨이 파드 전용)는 P1에서 이미 필수 — 새 게이트 없음. `X-User-*`가 승인 인가에 load-bearing.

## 테스트

**백엔드**: 기존 `CancelApprovalControllerIT`에 응답 DTO의 `requesterUserId`/`createdAt` 필드 단언 추가. 전체 payment-service 회귀 green 유지.

**프론트 E2E (Playwright, 실 스택)**
- ADMIN: 로그인 → 취소 요청 큐 → (요청 시드) 승인 → 목록에서 사라짐. 반려 → 사유 입력 → 사라짐.
- MERCHANT: 로그인 → 사이드바에 '취소 요청'만(상품/회원 링크 없음) → 본인 가맹점 요청만 보임 → 승인 가능. 타 가맹점 요청 안 보임.
- 비ADMIN·비MERCHANT(USER)로 /admin/cancel-requests 접근 → 로그인 리다이렉트.
- 대시보드 '대기 중 취소요청' 카운트가 큐 길이와 일치.

## 트레이드오프 / 메모

- **콘솔 공유**: 판매자 전용 포털 대신 어드민 콘솔을 role 게이트로 공유 — 신규 엔트리/인증 스캐폴딩 회피. 'admin' 브랜딩을 MERCHANT도 보는 절충(향후 판매자 포털 분리 가능).
- **role별 사이드바/라우트 가드는 UX 게이트일 뿐** — 실제 인가는 백엔드(게이트웨이 신뢰헤더 + ApprovalAuthorizer)가 강제. 프론트에서 MERCHANT가 URL로 /admin/users에 접근해도 백엔드가 403(회원 API는 ADMIN 전용).
- **paymentStatus 미포함**: 큐는 REQUESTED만 다루므로 승인 후 결제상태 표시 불필요 — 재조회로 갱신.
- **P3 예고**: 스토어프론트에서 USER 즉시취소 → 요청 제출로 전환 + USER 직접취소 인가 분기 제거는 P3. 이 P2는 승인자(ADMIN/MERCHANT) 측만.
