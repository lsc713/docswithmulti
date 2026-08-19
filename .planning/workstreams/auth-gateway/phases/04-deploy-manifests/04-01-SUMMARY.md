---
phase: 04-deploy-manifests
plan: 01
subsystem: infra
tags: [kubernetes, dockerfile, api-gateway, jwt, secret, ingress, spring-boot]

requires:
  - phase: 02-gateway (auth-gateway workstream)
    provides: api-gateway 무상태 게이트웨이 모듈(application.yml jwt.secret ${JWT_SECRET}, gateway.downstream.*-uri)
provides:
  - api-gateway 멀티스테이지 Dockerfile (docker build exit 0)
  - api-gateway 배포 매니페스트 Deployment+Service+Ingress (단일 외부 진입점)
  - jwt-secret Secret (placeholder, user-service와 공유하는 HS256 대칭키 슬롯)
  - app-config에 gateway downstream URI 2개
affects: [04-02 (user-service 매니페스트), 04-03 (NetworkPolicy·payment Ingress 이관·Java 무변경 게이트)]

tech-stack:
  added: []
  patterns:
    - "무상태 서비스 배포: DataSource env 없이 Deployment, SPRING_PROFILES_ACTIVE=prod로 local 프로파일 무력화"
    - "JWT 대칭키를 별도 jwt-secret Secret에 두고 gateway·user-service가 secretKeyRef로 공유"
    - "downstream URI를 Deployment env value + app-config ConfigMap 이중 배치(배포 설정 단일 출처)"

key-files:
  created:
    - api-gateway/Dockerfile
    - infra/k8s/apps/api-gateway.yaml
    - infra/k8s/apps/jwt-secret.yaml
  modified:
    - infra/k8s/apps/config.yaml

key-decisions:
  - "SPRING_PROFILES_ACTIVE=prod 강제 — application.yml 기본 profiles.active:local 무력화로 ${JWT_SECRET} fail-fast 활성(dev 기본키 기동 차단, T-04-02)"
  - "JWT_SECRET을 secretKeyRef(jwt-secret)로만 주입, 매니페스트엔 placeholder REPLACE_ME_AT_DEPLOY만(평문 하드코딩 금지, DEPLOY-03/T-04-01)"
  - "Ingress를 api-gateway:8000 단일 진입점으로 신설(payment Ingress 제거·이관은 04-03)"

patterns-established:
  - "무상태 게이트웨이 Deployment: env로 프로파일·Secret·downstream만, DataSource 없음"
  - "오프라인 매니페스트 검증: kubeconform 부재 시 python3 yaml.safe_load_all 파싱 + grep 구조 단언(라이브 클러스터 없음)"

requirements-completed: [DEPLOY-01, DEPLOY-03]

coverage:
  - id: D1
    description: "api-gateway 이미지가 신규 멀티스테이지 Dockerfile로 실제 빌드된다"
    requirement: "DEPLOY-01"
    verification:
      - kind: other
        ref: "docker build -t api-gateway:local -f api-gateway/Dockerfile ."
        status: pass
    human_judgment: false
  - id: D2
    description: "api-gateway.yaml(Deployment+Service+Ingress)가 정적 검증 통과, gateway가 단일 진입점(/) Ingress 보유"
    requirement: "DEPLOY-01"
    verification:
      - kind: other
        ref: "python3 yaml.safe_load_all(api-gateway.yaml, jwt-secret.yaml) + grep Ingress/path 단언 (kubeconform 부재 폴백)"
        status: pass
    human_judgment: false
  - id: D3
    description: "JWT_SECRET이 secretKeyRef(jwt-secret)로만 주입되고 매니페스트에 평문 대칭키 없음"
    requirement: "DEPLOY-03"
    verification:
      - kind: other
        ref: "grep secretKeyRef + grep 'name: jwt-secret' + ! grep -E 'JWT_SECRET: \"?[A-Za-z0-9+/]{32,}' (GATE_OK)"
        status: pass
    human_judgment: false
  - id: D4
    description: "gateway가 비-local(prod) 프로파일 + downstream URI env override로 기동하도록 매니페스트 구성"
    verification:
      - kind: other
        ref: "grep SPRING_PROFILES_ACTIVE=prod + GATEWAY_DOWNSTREAM_* in api-gateway.yaml"
        status: pass
    human_judgment: true
    rationale: "정적 매니페스트 구성은 검증됨. 실제 런타임 fail-fast·라우팅 동작은 라이브 클러스터 배포 후에만 확인 가능(오프라인 검증 한계)."

duration: 12min
completed: 2026-07-30
status: complete
---

# Phase 04 Plan 01: api-gateway 배포 tracer 슬라이스 Summary

**신규 멀티스테이지 Dockerfile로 api-gateway를 빌드하고, Deployment+Service+Ingress 매니페스트와 jwt-secret Secret(secretKeyRef 주입, 평문 없음)로 배포 계약을 정적으로 못박은 tracer 슬라이스**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-07-30T16:47 (KST)
- **Completed:** 2026-07-30T16:50 (KST)
- **Tasks:** 3
- **Files modified:** 4 (3 created, 1 modified)

## Accomplishments
- api-gateway 멀티스테이지 Dockerfile 신규 — `docker build` exit 0 (image 283MB). user-service 패턴 복사 + `COPY api-gateway/build.gradle`로 `:api-gateway:bootJar` task 해소, EXPOSE 8000.
- api-gateway.yaml (Deployment replicas 2 + Service + Ingress) — 단일 외부 진입점 `/`, containerPort 8000, /actuator/health readiness·liveness 프로브.
- JWT_SECRET을 신규 jwt-secret Secret에서 secretKeyRef로만 주입(평문 없음), SPRING_PROFILES_ACTIVE=prod로 fail-fast 경로 활성.
- config.yaml app-config에 gateway downstream URI 2개 추가(배포 설정 단일 출처).

## Task Commits

Each task was committed atomically:

1. **Task 1: api-gateway/Dockerfile 신규** - `6d512b2` (feat)
2. **Task 2: api-gateway.yaml + jwt-secret.yaml** - `d7e5f4e` (feat)
3. **Task 3: config.yaml downstream URI** - `296467b` (feat)

## Files Created/Modified
- `api-gateway/Dockerfile` - 멀티스테이지 빌드(gradle:8-jdk21 builder → temurin:21-jre-alpine runtime), EXPOSE 8000
- `infra/k8s/apps/api-gateway.yaml` - Deployment(app=api-gateway, prod 프로파일, JWT_SECRET secretKeyRef, downstream env) + Service:8000 + Ingress `/`
- `infra/k8s/apps/jwt-secret.yaml` - Secret jwt-secret, stringData JWT_SECRET=REPLACE_ME_AT_DEPLOY(placeholder)
- `infra/k8s/apps/config.yaml` - GATEWAY_DOWNSTREAM_PAYMENT_URI / GATEWAY_DOWNSTREAM_USER_URI 추가

## Decisions Made
None beyond 잠긴 결정 D-P4-1..8 — 계획대로 실행. 잠긴 결정 준수 요점:
- 순수 YAML(order.yaml 컨벤션), NS default, label app, 이미지 camelia9999/cancel-loadtest:api-gateway-latest, imagePullPolicy Always (D-P4-1)
- SPRING_PROFILES_ACTIVE=prod로 local 무력화 → ${JWT_SECRET} fail-fast (T-04-02)
- 평문 시크릿 금지, placeholder만 (DEPLOY-03/T-04-01)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None. docker build는 첫 시도에 BUILD SUCCESSFUL (settings.gradle의 api-gateway include에 대응하는 build.gradle COPY가 계획대로 포함되어 task-not-found 회피). kubeconform은 이 머신에 없어 계획된 python3 yaml 파싱 폴백으로 오프라인 검증(라이브 클러스터 없음). `kubectl --dry-run=client`는 서버 discovery가 필요해 사용하지 않음(계획 지시).

## Threat Model Coverage
- T-04-01 (Information Disclosure, jwt-secret): mitigate — placeholder만, grep 게이트로 평문 대칭키 부재 강제 ✓
- T-04-02 (Spoofing, dev 기본키 기동): mitigate — SPRING_PROFILES_ACTIVE=prod ✓
- T-04-03 (Tampering, gateway 우회): transfer — 04-03 NetworkPolicy로 이관(이 plan 범위 밖) ✓

새 위협 표면 발견 없음(신규 Ingress/Secret은 threat_model에 이미 등록됨).

## Next Phase Readiness
- 배포 계약(이미지 태그·Secret 이름·downstream env·prod 프로파일) 확정 — 04-02(user-service 매니페스트)·04-03(NetworkPolicy, payment Ingress 이관)이 딛고 갈 기반 완성.
- PHASE4_BASE = `63176ca` 캡처됨 — 04-03 Java 무변경 게이트 diff base. 이 plan은 Java 0 변경 확인.
- 배포 시 jwt-secret의 REPLACE_ME_AT_DEPLOY를 실제 256-bit HS256 키(user-service와 동일 값)로 교체 필요.

## Self-Check: PASSED
- api-gateway/Dockerfile: FOUND
- infra/k8s/apps/api-gateway.yaml: FOUND
- infra/k8s/apps/jwt-secret.yaml: FOUND
- infra/k8s/apps/config.yaml: FOUND (modified)
- Commit 6d512b2: FOUND
- Commit d7e5f4e: FOUND
- Commit 296467b: FOUND
- Java changes since PHASE4_BASE: NONE (게이트 통과)

---
*Phase: 04-deploy-manifests*
*Completed: 2026-07-30*
