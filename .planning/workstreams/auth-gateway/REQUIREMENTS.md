# REQUIREMENTS — Milestone v2.0: MSA cross-cutting (인증 + API Gateway)

> Workstream: `auth-gateway`. 취소 코어 4개 서비스(payment/order/merchant-limit/risk)는
> as-built·검증 완료 — 이 마일스톤 범위 밖(불변). 신규 인증 경계만 추가한다.
> 참고 소스: `origin/feat/user-product-resilience`(파일 단위 이식, **merge 금지** — main보다
> 110커밋 뒤처져 취소 코어 outbox/멱등성 재설계 이전 상태).

## v2.0 Requirements

### AUTH — 신원 & 토큰 발급 (user-service, 신규 모듈)

- [x] **AUTH-01**: 사용자는 이메일/비밀번호로 회원가입할 수 있다.
- [x] **AUTH-02**: 사용자는 로그인하여 access 토큰 + refresh 토큰을 발급받는다 (JWT, HMAC-SHA256).
- [x] **AUTH-03**: 사용자는 refresh 토큰으로 만료된 access 토큰을 갱신할 수 있다.
- [x] **AUTH-04**: 사용자는 로그아웃하여 자신의 refresh 토큰을 무효화할 수 있다.

### AUTHZ — 취소 인가 (payment-service, 서비스 레벨)

- [x] **AUTHZ-01**: 취소 요청은 게이트웨이가 전달한 검증된 신원의 역할(role)이 취소 권한을 가질 때만 처리되고, 권한이 없으면 403으로 거부된다.

### GATE — API Gateway (신규 모듈, Spring Cloud Gateway)

- [x] **GATE-01**: 클라이언트는 단일 게이트웨이 진입점을 통해 각 downstream 서비스로 라우팅된다.
- [x] **GATE-02**: 게이트웨이가 요청의 JWT를 검증하고, 검증된 신원(userId·role)을 신뢰 헤더로 downstream에 전달한다 (downstream 재검증 불필요).
- [x] **GATE-03**: 무효/만료/누락 토큰 요청은 게이트웨이에서 downstream 도달 전에 401로 차단된다.

### DEPLOY — 배포 매니페스트 (k3s, Phase 2/3에서 이관된 배포 시점 게이트)

- [x] **DEPLOY-01**: user-service·api-gateway가 기존 `infra/k8s/` 컨벤션(NS default·label app·ConfigMap/Secret 주입)에 맞는 Deployment/Service 매니페스트로 배포 가능하다 (api-gateway Dockerfile 포함).
- [x] **DEPLOY-02**: payment ingress가 NetworkPolicy로 api-gateway 파드에서만 8080 허용되어, payment 직접 도달 헤더 스푸핑이 차단된다.
- [x] **DEPLOY-03**: JWT_SECRET이 k3s Secret으로 user-service·api-gateway에 동일 값으로 주입되며(비-local fail-fast), 시크릿은 매니페스트에 하드코딩되지 않는다.

## Future Requirements (deferred)

- product-service 재고 연동(restock on cancel) — 별도 마일스톤 후보.
- 게이트웨이 rate-limiting / circuit breaker — 인증 경계 안정화 후.
- 분산추적(OTEL/Tempo) 앱 상시화 — 이번 스코프에서 제외(사용자 결정).

## Out of Scope (explicit)

- **취소 코어 4개 서비스 로직 변경**: as-built·검증 완료. 인증 헤더 소비(AUTHZ-01) 외 취소 플로우 불변.
- **브랜치 merge**: `feat/user-product-resilience`는 파일 참고만. 취소 코어를 되돌리는 merge 금지.
- **외부 IdP/OAuth2 소셜 로그인**: 자체 발급(user-service)만. SSO는 향후.
- **service discovery / config server**: 진입점이 게이트웨이 하나로 충분 — YAGNI.

## Traceability

| REQ-ID | Phase | Status |
|--------|-------|--------|
| AUTH-01 | Phase 1 | done |
| AUTH-02 | Phase 1 | done |
| AUTH-03 | Phase 1 | done |
| AUTH-04 | Phase 1 | done |
| AUTHZ-01 | Phase 3 | done |
| GATE-01 | Phase 2 | done |
| GATE-02 | Phase 2 | done |
| GATE-03 | Phase 2 | done |
| DEPLOY-01 | Phase 4 | done |
| DEPLOY-02 | Phase 4 | done |
| DEPLOY-03 | Phase 4 | done |
