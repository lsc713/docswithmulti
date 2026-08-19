---
phase: 4
slug: deploy-manifests
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-30
---

# Phase 4 — Validation Strategy

> 인프라 YAML + Dockerfile phase. 라이브 클러스터 없음 → 정적 검증만. Per-task map은 planner가 채운다.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | 오프라인 정적 검증: `kubeconform`(또는 python yaml 파싱 폴백) + `docker build`. ⚠ `kubectl --dry-run=client`는 서버 discovery 필요 → 클러스터 없으면 실패, 사용 금지 |
| **Config file** | infra/k8s/ (기존), api-gateway/Dockerfile (신규) |
| **Quick run command** | `kubeconform -strict -ignore-missing-schemas infra/k8s/<file>.yaml` (폴백 `python3 -c 'import yaml,sys;[list(yaml.safe_load_all(open(f))) for f in sys.argv[1:]]' <file>`) |
| **Full gate** | 전 신규/수정 매니페스트 오프라인 검증 통과 + `docker build -f api-gateway/Dockerfile` exit 0 |

---

## Sampling Rate

- **After every task commit:** 해당 매니페스트 오프라인 검증(kubeconform 또는 python yaml 파싱) 통과.
- **After wave:** 전 매니페스트 오프라인 검증 + api-gateway docker build.
- **Before verify:** 위 전체 + NetworkPolicy/Secret 정합 grep. 모두 클러스터 비의존.

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 01-T1 | 04-01 | 1 | DEPLOY-01 | — | docker build | `docker build -t api-gateway:local -f api-gateway/Dockerfile .` | api-gateway/Dockerfile | ⬜ pending |
| 01-T2 | 04-01 | 1 | DEPLOY-01, DEPLOY-03 | JWT_SECRET Secret 참조, 평문 없음 | offline validate + grep | `kubeconform`(폴백 python yaml) `jwt-secret.yaml api-gateway.yaml` + secretKeyRef/placeholder grep | api-gateway.yaml, jwt-secret.yaml | ⬜ pending |
| 01-T3 | 04-01 | 1 | DEPLOY-01 | — | offline validate + grep | `kubeconform`(폴백 python yaml) `config.yaml` + GATEWAY_DOWNSTREAM_* grep | config.yaml | ⬜ pending |
| 02-T1 | 04-02 | 2 | DEPLOY-01, DEPLOY-03 | DB 자격 Secret 참조 | offline validate + grep | `kubeconform`(폴백 python yaml) `config.yaml` + USER_DB_URL/USER grep | config.yaml | ⬜ pending |
| 02-T2 | 04-02 | 2 | DEPLOY-01, DEPLOY-03 | JWT 공유 Secret, 평문 비밀번호 없음 | offline validate + grep | `kubeconform`(폴백 python yaml) `user-service.yaml` + jwt-secret/secretKeyRef grep | user-service.yaml | ⬜ pending |
| 03-T1 | 04-03 | 2 | DEPLOY-02 | payment ingress ← gateway only | offline validate + grep | `kubeconform`(폴백 python yaml) `payment-ingress.yaml` + podSelector/from/port grep | payment-ingress.yaml | ⬜ pending |
| 03-T2 | 04-03 | 2 | DEPLOY-02 | 단일 진입점 이관, Java 무변경 | offline validate + grep | `kubeconform`(폴백 python yaml) `payment.yaml` + `! grep "kind: Ingress"` + `git diff "$PHASE4_BASE"..HEAD -- '*.java'` empty + deploy.sh refs | payment.yaml, deploy.sh | ⬜ pending |

---

## Wave 0 Requirements

- [x] Wave 0 불요 — 정적 인프라 phase. 테스트 스캐폴드 대신 기존 도구(kubectl/docker)가 게이트. 04-01 Task 1(Dockerfile)이 사실상 tracer의 첫 게이트.
- 게이트 도구: `kubectl apply --dry-run=client` + `docker build` (설치 불요). kubeconform 없음(선택, dry-run으로 충분 — D-P4-7).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| NetworkPolicy 실제 강제(payment 직접 도달 차단) | DEPLOY-02 | 라이브 k3s 클러스터 필요 | 배포 후 `kubectl exec`로 non-gateway 파드에서 payment:8080 접근 시 타임아웃 확인 |
| rollout/readiness 실동작 | DEPLOY-01 | 라이브 클러스터 필요 | 배포 후 `kubectl rollout status` |

---

## Validation Sign-Off

- [ ] 전 매니페스트 오프라인 검증(kubeconform 또는 python yaml 파싱) 통과 — 클러스터 비의존
- [ ] api-gateway docker build exit 0
- [ ] NetworkPolicy/Secret 정합(payment←gateway, 평문 시크릿 없음)
- [ ] 취소 코어 Java 무변경
- [ ] `nyquist_compliant: true` set

**Approval:** pending
