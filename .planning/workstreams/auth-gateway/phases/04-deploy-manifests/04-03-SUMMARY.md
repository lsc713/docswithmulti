---
phase: 04-deploy-manifests
plan: 03
subsystem: infra
tags: [kubernetes, networkpolicy, ingress, k3s, deploy, gateway]

requires:
  - phase: 04-01
    provides: api-gateway Deployment+Service+Ingress(단일 진입점), jwt-secret Secret
provides:
  - payment:8080 ingress를 app: api-gateway 파드로만 제한하는 NetworkPolicy(payment-allow-gateway)
  - payment.yaml 기존 / Ingress 제거 → gateway 단일 외부 진입점 이관
  - deploy.sh 배포 순서에 jwt-secret·user-service·api-gateway·networkpolicy 편입
affects: [phase 05, deploy, security-gate, load-test]

tech-stack:
  added: [networking.k8s.io/v1 NetworkPolicy]
  patterns:
    - "배포 토폴로지로 게이트웨이 단일 검증 봉인 — payment 직접 도달을 NetworkPolicy로 물리 차단"
    - "flow-style YAML 매니페스트(프로젝트 관례) 유지"

key-files:
  created:
    - infra/k8s/networkpolicy/payment-ingress.yaml
  modified:
    - infra/k8s/apps/payment.yaml
    - infra/k8s/deploy.sh

key-decisions:
  - "NetworkPolicy는 egress 미지정 — default-deny 미도입이므로 최소 정책(Open Question 2)"
  - "networkpolicy는 앱 rollout 완료 후 apply — 파드 기동 순서 안정성"

patterns-established:
  - "진입점 이관: payment Ingress 제거 + api-gateway Ingress 단일화(grep 부재 강제)"

requirements-completed: [DEPLOY-02]

coverage:
  - id: D1
    description: "payment:8080 ingress를 NetworkPolicy로 app: api-gateway 파드에서만 허용(DEPLOY-02)"
    requirement: "DEPLOY-02"
    verification:
      - kind: other
        ref: "grep 구조 단언 + python yaml 파싱: kind NetworkPolicy / podSelector app: payment / from app: api-gateway / port 8080 (NETPOL_OK)"
        status: pass
    human_judgment: false
  - id: D2
    description: "payment.yaml 기존 / Ingress 제거 → gateway 단일 외부 진입점 이관"
    requirement: "DEPLOY-02"
    verification:
      - kind: other
        ref: "! grep 'kind: Ingress' infra/k8s/apps/payment.yaml + python yaml 파싱 (INGRESS_MOVED_OK)"
        status: pass
    human_judgment: false
  - id: D3
    description: "deploy.sh가 jwt-secret·user-service·api-gateway·networkpolicy를 순서대로 배포"
    verification:
      - kind: other
        ref: "grep user-service.yaml/api-gateway.yaml/jwt-secret.yaml/payment-ingress.yaml in deploy.sh + bash -n(DEPLOY_SH_SYNTAX_OK)"
        status: pass
    human_judgment: false
  - id: D4
    description: "실 클러스터에서 NetworkPolicy가 gateway 외 파드의 payment:8080 도달을 실제 거부"
    verification: []
    human_judgment: true
    rationale: "오프라인 검증만 수행(kubectl dry-run 금지). k3s flannel + NetworkPolicy controller의 실제 강제는 클러스터 배포 후 확인 필요."

duration: 6min
completed: 2026-07-30
status: complete
---

# Phase 04 Plan 03: NetworkPolicy 진입점 봉인 Summary

**payment:8080 ingress를 api-gateway 파드로만 허용하는 NetworkPolicy 신규 + payment `/` Ingress 제거로 gateway 단일 진입점 이관, deploy.sh 배포 순서 편입**

## Performance

- **Duration:** ~6 min
- **Tasks:** 2
- **Files created:** 1
- **Files modified:** 2

## Accomplishments
- `payment-allow-gateway` NetworkPolicy: podSelector `app: payment` 대상, ingress from `app: api-gateway` port 8080 — gateway 우회 직접 도달(신뢰 헤더 X-User-Role 스푸핑 벡터, T-04-07) 물리 차단
- payment.yaml 기존 `/` Ingress 제거 → api-gateway Ingress(04-01)로 진입점 이관, gateway 단일 외부 진입점 성립(T-04-08). Deployment/Service/노드풀/anti-affinity/preStop 스펙 전부 불변(D-P4-8)
- deploy.sh 순서 갱신: config에 jwt-secret 동반 apply, 앱 배포에 user-service·api-gateway 추가(rollout status 포함), 앱 rollout 후 networkpolicy apply

## Task Commits

1. **Task 1: NetworkPolicy 신규** - `4c462c8` (feat)
2. **Task 2: payment Ingress 제거 + deploy.sh 순서 갱신** - `d9a3e24` (feat)

## Files Created/Modified
- `infra/k8s/networkpolicy/payment-ingress.yaml` - payment:8080을 api-gateway 파드로만 제한하는 NetworkPolicy (신규)
- `infra/k8s/apps/payment.yaml` - 기존 `/` Ingress 블록 제거(Deploy/Svc 불변)
- `infra/k8s/deploy.sh` - jwt-secret·user-service·api-gateway·networkpolicy 배포 순서 편입

## Decisions Made
- NetworkPolicy egress 미지정 — default-deny 미도입 상태라 최소 정책 유지(Open Question 2). gateway→payment egress는 이미 허용됨.
- networkpolicy는 앱 rollout 완료 후 apply — 파드 기동 순서 안정성 확보.
- YAML은 프로젝트 관례인 flow-style로 작성해 기존 매니페스트와 일관성 유지.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## Verification Results
- payment-ingress.yaml: python yaml 파싱 통과 + grep 구조 단언(kind NetworkPolicy / app: payment / app: api-gateway / port 8080) → NETPOL_OK
- payment.yaml: python yaml 파싱 통과 + `! grep "kind: Ingress"` → INGRESS_MOVED_OK
- deploy.sh: `bash -n` 통과(DEPLOY_SH_SYNTAX_OK), jwt-secret·user-service·api-gateway·payment-ingress 참조 확인
- 취소 코어 Java 무변경 게이트: `git diff --name-only 63176ca..HEAD -- '*.java'` 빈 결과 (D-P4-8 준수)
- kubectl --dry-run 미사용(정책 준수) — 오프라인 검증만 수행

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- payment 보안 게이트(NetworkPolicy)와 gateway 단일 진입점이 매니페스트로 봉인됨. Phase 04 deploy-manifests 목표(D-P4-5) 충족.
- 실 클러스터 배포 시 NetworkPolicy 실제 강제 여부는 배포 후 검증 필요(k3s flannel controller 활성 전제).

## Self-Check: PASSED
- infra/k8s/networkpolicy/payment-ingress.yaml: FOUND
- infra/k8s/apps/payment.yaml (Ingress 제거): FOUND, `! grep "kind: Ingress"` 통과
- infra/k8s/deploy.sh: FOUND, 신규 참조 4종 확인
- Commit 4c462c8: FOUND
- Commit d9a3e24: FOUND

---
*Phase: 04-deploy-manifests*
*Completed: 2026-07-30*
