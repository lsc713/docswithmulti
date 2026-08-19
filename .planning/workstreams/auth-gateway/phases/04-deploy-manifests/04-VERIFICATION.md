---
phase: 04-deploy-manifests
workstream: auth-gateway
milestone: v2.0
verified: 2026-07-30T00:00:00Z
status: passed
score: 10/10 must-haves verified
behavior_unverified: 0
overrides_applied: 0
re_verification: null
gaps: []
deferred: []
manual_deploy_time_checks: # Non-blocking — declared Manual-Only, live cluster required (out of static scope, honestly scoped)
  - test: "비-gateway 파드에서 payment:8080 접근 시 NetworkPolicy로 타임아웃/거부되는지"
    expected: "app!=api-gateway 파드는 payment:8080 도달 실패, api-gateway 파드는 성공"
    why_human: "k3s flannel + NetworkPolicy controller의 실제 강제는 라이브 클러스터에서만 관측 가능"
  - test: "SPRING_PROFILES_ACTIVE=prod로 gateway/user-service 기동 시 JWT_SECRET 미주입 fail-fast"
    expected: "${JWT_SECRET} 미해결 시 컨텍스트 로드 실패로 기동 중단(dev 기본키 미사용)"
    why_human: "런타임 부트스트랩 바인딩 실패는 실제 기동에서만 관측 가능"
  - test: "rollout/readiness 실동작"
    expected: "kubectl rollout status deploy/user-service deploy/api-gateway 성공"
    why_human: "라이브 클러스터 필요"
---

# Phase 4: 배포 매니페스트 k3s Verification Report

**Phase Goal:** v2.0 인증 경계(user-service·api-gateway)를 기존 infra/k8s 컨벤션 매니페스트로 배포 가능하게 + 보안 게이트(payment ingress NetworkPolicy · JWT_SECRET Secret) 매니페스트 강제. 정적 검증만(라이브 클러스터 없음).
**Verified:** 2026-07-30
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

This is an explicitly **static-only** phase (no live cluster). The goal is manifest correctness + security-gate enforcement *in the manifests*, not live runtime. Every static success criterion is verified against the real artifacts (not SUMMARY claims). Runtime/live-cluster behaviors are honestly declared Manual-Only and listed as non-blocking deploy-time checks.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | 신규 2서비스(user-service·api-gateway) 배포 매니페스트 정적검증 통과 (SC1) | ✓ VERIFIED | `python3 yaml.safe_load_all` 전 매니페스트 파싱 `ALL_PARSE_OK`. kubeconform 미설치 → 계획된 python 폴백 경로 사용 |
| 2 | api-gateway 이미지가 신규 Dockerfile로 실제 빌드된다 (DEPLOY-01) | ✓ VERIFIED | **독립 재실행** `docker build -f api-gateway/Dockerfile .` → EXIT=0, image `api-gateway:verify` 생성. 전제조건: settings.gradle L10 `include 'api-gateway'`, api-gateway/build.gradle 존재 + Dockerfile에 `COPY api-gateway/build.gradle` 포함(task-not-found 회피) |
| 3 | api-gateway가 단일 외부 진입점(/) Ingress 보유 (DEPLOY-01) | ✓ VERIFIED | api-gateway.yaml에 Ingress `path: / pathType: Prefix backend api-gateway:8000` 존재 |
| 4 | gateway·user-service에 JWT_SECRET이 secretKeyRef(jwt-secret) 동일 Secret으로 주입, 평문 없음 (DEPLOY-03, SC3) | ✓ VERIFIED | 양쪽 yaml 모두 `secretKeyRef {name: jwt-secret, key: JWT_SECRET}`. jwt-secret.yaml `JWT_SECRET: REPLACE_ME_AT_DEPLOY` placeholder만; 평문 대칭키 grep(`[A-Za-z0-9+/]{32,}`) `NO_PLAINTEXT` |
| 5 | user-service DB 자격이 db-cred/app-config 참조로만 주입, 매니페스트 평문 비밀번호 리터럴 없음 | ✓ VERIFIED | user-service.yaml: URL←configMapKeyRef(app-config.USER_DB_URL), USER/PASS←secretKeyRef(db-cred). "user"/"user"는 db-cred Secret stringData(기존 컨벤션 동일, 다른 서비스도 동형), Deployment엔 리터럴 없음 |
| 6 | payment 8080 ingress가 NetworkPolicy로 app:api-gateway 파드에서만 허용 (DEPLOY-02, SC2) | ✓ VERIFIED | payment-ingress.yaml: `podSelector app:payment`(target) + `ingress.from podSelector app:api-gateway`(from) + `port 8080`. selector 방향 정확 |
| 7 | payment.yaml 기존 Ingress 제거 → gateway 단일 진입점 이관 (DEPLOY-02) | ✓ VERIFIED | `grep kind:Ingress payment.yaml` 없음. diff: Ingress 블록 + `---` 삭제, 주석 1줄만 추가. Deployment/Service spec 불변 |
| 8 | deploy.sh가 config/secret → 신규 서비스 → networkpolicy 순서로 배포 | ✓ VERIFIED | deploy.sh: config.yaml+jwt-secret.yaml apply → 6앱(payment/risk/merchant-limit/order/user-service/api-gateway) rollout → networkpolicy/payment-ingress.yaml apply |
| 9 | 취소 코어 Java 무변경 (D-P4-8) | ✓ VERIFIED | `git diff --name-only 63176ca..HEAD -- '*.java'` 빈 결과. diff --stat: 8파일 전부 infra/YAML+Dockerfile |
| 10 | 오프라인 검증이 진짜 오프라인 (dry-run 미사용) | ✓ VERIFIED | 모든 `<automated>` 게이트가 `kubeconform || python3 yaml` 폴백 패턴. `dry-run` 문자열은 PLAN 산문/done 서술("dry-run 통과"=정적검증 통과 관용표현)에만 등장, 실 게이트엔 없음. VALIDATION.md가 `kubectl --dry-run=client` 명시적 금지 |

**Score:** 10/10 truths verified (0 present, behavior-unverified)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `api-gateway/Dockerfile` | 멀티스테이지, EXPOSE 8000, api-gateway/build.gradle COPY | ✓ VERIFIED | build EXIT=0, EXPOSE 8000, gradle:8-jdk21→temurin:21-jre-alpine |
| `infra/k8s/apps/api-gateway.yaml` | Deploy+Svc+Ingress, NS default, app label, camelia9999 이미지, 헬스프로브 | ✓ VERIFIED | 컨벤션 일치, SPRING_PROFILES_ACTIVE=prod, containerPort 8000 |
| `infra/k8s/apps/jwt-secret.yaml` | Secret jwt-secret, placeholder만 | ✓ VERIFIED | REPLACE_ME_AT_DEPLOY, 평문 없음 |
| `infra/k8s/apps/user-service.yaml` | Deploy+Svc, JWT/DB env 주입 | ✓ VERIFIED | port 8085, jwt-secret 공유, db-cred 참조 |
| `infra/k8s/networkpolicy/payment-ingress.yaml` | payment←api-gateway:8080 제한 | ✓ VERIFIED | selector 방향 정확 |
| `infra/k8s/apps/config.yaml` | GATEWAY_DOWNSTREAM_* + USER_DB_* 추가 | ✓ VERIFIED | 5개 키 존재, 기존 4서비스 키 불변 |
| `infra/k8s/deploy.sh` | 순서 편입 | ✓ VERIFIED | jwt-secret/신규2서비스/networkpolicy 순서 반영 |

### Key Link Verification

| From | To | Via | Status |
|------|----|----|--------|
| api-gateway.yaml / user-service.yaml | jwt-secret Secret | secretKeyRef(jwt-secret.JWT_SECRET) 양쪽 동일 | ✓ WIRED |
| api-gateway.yaml env | gateway.downstream.*-uri | GATEWAY_DOWNSTREAM_PAYMENT_URI/USER_URI 릴랙스드 바인딩 | ✓ WIRED |
| user-service.yaml | app-config/db-cred | SPRING_DATASOURCE_* ← USER_DB_URL/USER/PASS | ✓ WIRED |
| payment.yaml Ingress 제거 | api-gateway Ingress | 진입점 이관 | ✓ WIRED |
| NetworkPolicy | payment 파드 | podSelector app:payment ← from app:api-gateway:8080 | ✓ WIRED |

### Requirements Coverage

| Requirement | Description | Status | Evidence |
|-------------|-------------|--------|----------|
| DEPLOY-01 | 신규 서비스 배포 매니페스트 + api-gateway Dockerfile | ✓ SATISFIED | 매니페스트 정적검증 통과 + docker build EXIT=0(독립 재실행) |
| DEPLOY-02 | payment ingress를 api-gateway에서만 허용 NetworkPolicy | ✓ SATISFIED | NetworkPolicy selector 방향 정확 + payment Ingress 제거 |
| DEPLOY-03 | JWT_SECRET Secret 공유 주입, 평문 없음 | ✓ SATISFIED | 양쪽 secretKeyRef 동일 Secret, placeholder만 |

### Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| — | debt markers(TBD/FIXME/XXX) 없음 | — | 없음 |
| jwt-secret.yaml / config.yaml | dev 자격 "user"/"user", DB pass 리터럴 | ℹ️ Info | 모두 Secret stringData(db-cred/jwt-secret) 내부 — 기존 컨벤션과 동형. JWT는 placeholder. Deployment 매니페스트엔 평문 없음. 비-blocking |

### Manual Verification Required (deploy-time, non-blocking)

정적 phase 범위 밖으로 정직하게 표기된 라이브 항목 — SUMMARY가 과대주장 없이 Manual-Only로 선언(04-01/02 rationale, 04-03 T3 verification:[] + rationale). 정적 goal 달성을 막지 않음:

1. **NetworkPolicy 실제 강제** — 비-gateway 파드의 payment:8080 도달 차단(라이브 k3s)
2. **런타임 fail-fast** — prod 프로파일 + JWT_SECRET 미주입 시 기동 중단
3. **rollout/readiness 실동작**

### Gaps Summary

없음. 정적 매니페스트 phase의 선언된 범위(배포 매니페스트 정적검증 + 보안 게이트 매니페스트 강제)가 실제 아티팩트 기준으로 전부 달성됨. 가장 강한 게이트(docker build)를 독립 재실행해 EXIT=0 확인, Java 무변경 diff 확인, Secret 참조/평문부재/selector 방향/진입점 이관 모두 코드로 검증. 라이브 런타임 동작은 정직하게 Manual-Only로 이관(과대주장 없음).

---

_Verified: 2026-07-30_
_Verifier: Claude (gsd-verifier)_
