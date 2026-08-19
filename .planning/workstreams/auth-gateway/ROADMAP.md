# ROADMAP — Milestone v2.0: MSA cross-cutting (인증 + API Gateway)

> Workstream: `auth-gateway`. 취소 코어 4개 서비스(payment/order/merchant-limit/risk)는
> as-built·검증 완료 — 범위 밖(불변). 이 마일스톤은 인증 경계만 추가한다.
> 참고 소스: `origin/feat/user-product-resilience`(파일 단위 이식, **merge 금지**).

**Granularity:** standard · **Phases:** 4 · **Coverage:** 11/11 requirements mapped

## Phases

- [x] **Phase 1: user-service 인증 기반** - 회원가입·로그인·토큰 갱신·로그아웃 (JWT 발급/무효화) (completed 2026-07-30)
- [x] **Phase 2: API Gateway (JWT 검증 + 라우팅)** - 단일 진입점, JWT 검증, 신뢰 헤더 전달, 미인증 401 차단 (completed 2026-07-30)
- [x] **Phase 3: payment 취소 인가** - 게이트웨이 신뢰 헤더의 role로 취소 권한 인가 (403 거부) (2 plans planned) (completed 2026-07-30)
- [x] **Phase 4: 배포 매니페스트 (k3s)** - 신규 서비스 배포 + NetworkPolicy/JWT Secret로 인증 경계를 배포 시점에 강제 (completed 2026-07-30)

## Phase Details

### Phase 1: user-service 인증 기반
**Goal**: 사용자가 자체 계정으로 신원을 만들고 JWT 토큰 수명주기(발급·갱신·무효화)를 관리할 수 있다.
**Depends on**: Nothing (first phase)
**Requirements**: AUTH-01, AUTH-02, AUTH-03, AUTH-04
**Success Criteria** (what must be TRUE):
  1. 사용자가 이메일/비밀번호로 회원가입하면 계정이 생성되고, 중복 이메일은 거부된다.
  2. 사용자가 로그인하면 access 토큰 + refresh 토큰(JWT, HMAC-SHA256)을 응답으로 받는다.
  3. 만료된 access 토큰을 유효한 refresh 토큰으로 제출하면 새 access 토큰을 발급받는다.
  4. 사용자가 로그아웃하면 자신의 refresh 토큰이 무효화되어 더 이상 갱신에 사용할 수 없다.
**Plans**: 2 plans
- [ ] 01-01-PLAN.md — [tracer, wave 1] user-service 모듈 이식(스캐폴드+AUTH 슬라이스+트림+보안조정) + signup→login→access+refresh 통합테스트 그린 (AUTH-01, AUTH-02)
- [ ] 01-02-PLAN.md — [wave 2] AUTH-03(refresh 갱신)·AUTH-04(logout 무효화) 통합 검증 + JWT_SECRET fail-fast + docker-compose mysql-user (AUTH-03, AUTH-04)

### Phase 2: API Gateway (JWT 검증 + 라우팅)
**Goal**: 클라이언트의 모든 요청이 단일 게이트웨이를 통과하며, 게이트웨이가 JWT를 검증하고 검증된 신원을 downstream에 신뢰 헤더로 전달한다.
**Depends on**: Phase 1 (검증할 JWT를 발급하는 user-service가 존재해야 함)
**Requirements**: GATE-01, GATE-02, GATE-03
**Success Criteria** (what must be TRUE):
  1. 클라이언트가 단일 게이트웨이 진입점으로 요청하면 의도한 downstream 서비스로 라우팅된다.
  2. 유효한 JWT를 담은 요청은 통과하고, downstream은 게이트웨이가 실은 userId·role 신뢰 헤더를 수신한다.
  3. 무효·만료·누락 토큰 요청은 downstream에 도달하기 전 게이트웨이에서 401로 차단된다.
**Plans**: 2 plans
- [x] 02-01-PLAN.md — [tracer, wave 1] api-gateway 모듈 스캐폴드(Spring Cloud webmvc BOM↔Boot 4.0.5 빌드 정합) + 유효 JWT→payment 라우팅→WireMock downstream이 X-User-Id/X-User-Role 수신 통합테스트 그린 (GATE-01, GATE-02) — SUMMARY 02-01 (e5725f0, 16e9bcb)
- [ ] 02-02-PLAN.md — [wave 2] 401 차단 3종(누락/무효/만료, downstream 무호출) + 헤더 strip 스푸핑 회귀 + 공개경로(signup/login/refresh) vs 인증경로 + JwtVerifier 단위 + error-catalog 등록 (GATE-01, GATE-02, GATE-03)

### Phase 3: payment 취소 인가
**Goal**: payment-service가 게이트웨이가 전달한 신뢰된 신원의 role만으로 취소 권한을 인가하고, 권한 없는 요청을 거부한다.
**Depends on**: Phase 2 (게이트웨이가 userId·role 신뢰 헤더를 전달해야 서비스 레벨 인가가 가능)
**Requirements**: AUTHZ-01
**Success Criteria** (what must be TRUE):
  1. 취소 권한 role을 가진 신원의 취소 요청은 기존 취소 플로우(멱등성·TX 경계 불변)로 정상 처리된다.
  2. 취소 권한이 없는 role의 취소 요청은 취소 플로우 진입 전 403으로 거부된다.
  3. payment-service는 헤더의 role만 신뢰하여 인가하고 JWT를 재검증하지 않는다(게이트웨이 단일 검증 원칙 유지).
**Plans**: 2 plans
- [ ] 03-01-PLAN.md — [tracer, wave 1] 인가 클래스(CancelNotAuthorized PORT·CancelAuthorizer POJO·CancelAuthorization UseCase/Service·AuthenticatedUser) + CancelController pre-check 배선 + USER→403 e2e + 취소 코어 불변 게이트 (AUTHZ-01)
- [ ] 03-02-PLAN.md — [wave 2] 인가 매트릭스 6종 domain 단위 + 오케스트레이션(ADMIN 로드 생략/MERCHANT 로드·404/비정상 헤더 403) + ADMIN pass-through 컨트롤러 증명(SC#1) + domain-rules 인가 정책·NetworkPolicy 신뢰 경계 문서화 (AUTHZ-01)

### Phase 4: 배포 매니페스트 (k3s)
**Goal**: v2.0 인증 경계(user-service·api-gateway)를 기존 `infra/k8s/` 컨벤션에 맞는 매니페스트로 배포 가능하게 하고, Phase 2/3에서 배포 시점으로 이관한 보안 게이트(payment ingress NetworkPolicy · JWT_SECRET Secret)를 매니페스트로 강제한다.
**Depends on**: Phase 1·2·3 (배포할 신규 서비스 + 강제할 인증 경계가 존재해야 함)
**Requirements**: DEPLOY-01, DEPLOY-02, DEPLOY-03
**Success Criteria** (what must be TRUE):
  1. user-service·api-gateway가 Deployment/Service 매니페스트 + api-gateway Dockerfile로 배포 가능하고, kubectl dry-run + kubeconform 정적 검증을 통과한다.
  2. payment의 8080 ingress가 NetworkPolicy로 api-gateway 파드에서만 허용되고(그 외 거부), 게이트웨이가 단일 외부 진입점이 된다(payment `/` Ingress 이관).
  3. JWT_SECRET이 k3s Secret으로 user-service·api-gateway에 동일 값으로 주입되며 매니페스트에 평문 시크릿이 없다.
**결정(사용자 확정)**: user_db는 기존 외부 MySQL(10.0.1.30~32)에 스키마 추가(기존 *_DB_URL 컨벤션) · 검증은 정적(dry-run/kubeconform)만(라이브 클러스터 없음).
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. user-service 인증 기반 | 2/2 | Complete | 2026-07-30 |
| 2. API Gateway (JWT 검증 + 라우팅) | 2/2 | Complete | 2026-07-30 |
| 3. payment 취소 인가 | 2/2 | Complete | 2026-07-30 |
| 4. 배포 매니페스트 (k3s) | 3/3 | Complete | 2026-07-30 |

## Coverage

✓ All 11 v2.0 requirements mapped to exactly one phase
✓ No orphaned requirements, no duplicates

| REQ-ID | Phase |
|--------|-------|
| AUTH-01 | Phase 1 |
| AUTH-02 | Phase 1 |
| AUTH-03 | Phase 1 |
| AUTH-04 | Phase 1 |
| GATE-01 | Phase 2 |
| GATE-02 | Phase 2 |
| GATE-03 | Phase 2 |
| AUTHZ-01 | Phase 3 |
| DEPLOY-01 | Phase 4 |
| DEPLOY-02 | Phase 4 |
| DEPLOY-03 | Phase 4 |
