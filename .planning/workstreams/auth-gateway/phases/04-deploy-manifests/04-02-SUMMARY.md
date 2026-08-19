---
phase: 04-deploy-manifests
plan: 02
subsystem: infra
tags: [kubernetes, user-service, jwt, secret, mysql, spring-boot]

requires:
  - phase: 04-01 (auth-gateway workstream)
    provides: jwt-secret Secret(HS256 대칭키 슬롯), app-config GATEWAY_DOWNSTREAM_* 키
provides:
  - user-service 배포 매니페스트 Deployment+Service (port 8085)
  - app-config USER_DB_URL(외부 MySQL user_db) + db-cred USER 계정
  - gateway↔user-service 동일 jwt-secret Secret 공유(대칭키 일치)
affects: [04-03 (NetworkPolicy·payment Ingress 이관)]

tech-stack:
  added: []
  patterns:
    - "상태 서비스 배포: DataSource env(URL configMapKeyRef, USER/PASS secretKeyRef) + SPRING_PROFILES_ACTIVE=prod"
    - "JWT 대칭키를 gateway와 동일 jwt-secret Secret에서 secretKeyRef로 공유 주입"
    - "외부 MySQL 스키마 추가 컨벤션: *_DB_URL(app-config) + *_DB_USER/PASS(db-cred)"

key-files:
  created:
    - infra/k8s/apps/user-service.yaml
  modified:
    - infra/k8s/apps/config.yaml

key-decisions:
  - "user_db를 payment 노드(10.0.1.30:3306)에 스키마 추가 — 기존 *_DB_URL 컨벤션 준수, host는 calibration knob"
  - "db-cred에 user 전용 계정(user/user) 추가 — 서비스별 개별 계정 원칙, user-service local 기본값과 일치"
  - "envFrom(app-config) 생략 — user-service는 redis/kafka 키를 안 씀, 필요한 값만 개별 주입"

requirements-completed: [DEPLOY-01, DEPLOY-03]

coverage:
  - id: D1
    description: "user-service.yaml(Deployment+Service)가 정적 검증 통과, port 8085 컨벤션 매니페스트"
    requirement: "DEPLOY-01"
    verification:
      - kind: other
        ref: "python3 yaml.safe_load_all(user-service.yaml) + grep 구조 단언 → USERSVC_OK (kubeconform 부재 폴백)"
        status: pass
    human_judgment: false
  - id: D2
    description: "JWT_SECRET이 04-01과 동일 jwt-secret Secret에서 secretKeyRef로 주입(gateway 대칭키 공유)"
    requirement: "DEPLOY-03"
    verification:
      - kind: other
        ref: "grep 'name: jwt-secret' + api-gateway.yaml 동일 secretKeyRef(name jwt-secret, key JWT_SECRET) 대조"
        status: pass
    human_judgment: false
  - id: D3
    description: "DB 자격이 db-cred/app-config 참조로만 주입되고 매니페스트에 평문 비밀번호 리터럴 없음"
    requirement: "DEPLOY-03"
    verification:
      - kind: other
        ref: "grep -A1 SPRING_DATASOURCE_PASSWORD → secretKeyRef + plaintext 리터럴 부재 grep → GATE_OK"
        status: pass
    human_judgment: false
  - id: D4
    description: "SPRING_PROFILES_ACTIVE=prod로 local 프로파일 무력화(localhost:3315·dev키 대신 USER_DB_URL·fail-fast)"
    verification:
      - kind: other
        ref: "grep SPRING_PROFILES_ACTIVE value prod in user-service.yaml"
        status: pass
    human_judgment: true
    rationale: "정적 매니페스트 구성은 검증됨. 실제 런타임 fail-fast·DB 연결·대칭키 401 동작은 라이브 클러스터 배포 후에만 확인 가능(오프라인 검증 한계)."

duration: 2min
completed: 2026-07-30
status: complete
---

# Phase 04 Plan 02: user-service 배포 매니페스트 Summary

**user-service를 기존 배포 컨벤션(order.yaml 패턴)으로 매니페스트화하고, 04-01의 jwt-secret Secret을 gateway와 동일하게 secretKeyRef 공유 주입해 HS256 대칭키 일치를 보장하며, user_db 자격을 app-config/db-cred 참조로만(평문 없이) 연결**

## Performance

- **Duration:** ~2 min
- **Started:** 2026-07-30T07:53Z
- **Completed:** 2026-07-30T07:55Z
- **Tasks:** 2
- **Files modified:** 2 (1 created, 1 modified)

## Accomplishments
- config.yaml app-config에 USER_DB_URL(외부 MySQL user_db, 10.0.1.30:3306) 추가, db-cred에 USER_DB_USER/PASS(user/user) 추가 — 04-01 downstream 키 및 취소 코어 4서비스 키·Secret 보존.
- user-service.yaml(Deployment replicas 2 + Service, containerPort/port/targetPort 8085) 신규 — order.yaml DataSource 주입 패턴 동일.
- JWT_SECRET을 04-01과 동일 jwt-secret Secret에서 secretKeyRef로 주입(gateway↔user-service 대칭키 공유, api-gateway.yaml과 동일 name/key 대조 확인).
- SPRING_PROFILES_ACTIVE=prod로 application.yml 기본 profiles.active:local 무력화 → localhost:3315 대신 USER_DB_URL, ${JWT_SECRET} fail-fast.
- 매니페스트에 평문 비밀번호·대칭키 리터럴 부재(모두 secretKeyRef, DEPLOY-03).

## Task Commits

1. **Task 1: config.yaml USER_DB_URL + db-cred user 계정** - `6af6852` (feat)
2. **Task 2: user-service.yaml (Deployment+Service)** - `c425699` (feat)

## Files Created/Modified
- `infra/k8s/apps/user-service.yaml` - Deployment(app=user-service, prod 프로파일, JWT_SECRET secretKeyRef, DataSource env) + Service:8085. readiness/liveness `/actuator/health/*` port 8085, resources requests cpu 500m/memory 1Gi.
- `infra/k8s/apps/config.yaml` - USER_DB_URL(app-config), USER_DB_USER/USER_DB_PASS(db-cred) 추가

## Decisions Made
잠긴 결정 D-P4-1/3/4/8 준수, 계획대로 실행:
- user_db host 10.0.1.30 선택(payment 노드에 스키마 추가) — Claude 재량 calibration knob, 다른 노드 배치 시 이 값만 조정
- envFrom(app-config) 생략 — user-service 미사용 키(redis/kafka)뿐이라 필요한 값만 개별 주입(최소)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None. kubeconform은 이 머신에 없어 계획된 python3 yaml.safe_load_all 파싱 폴백으로 오프라인 검증(`kubectl --dry-run=client`은 서버 discovery 필요해 계획 지시대로 미사용, 라이브 클러스터 없음). 취소 코어/Java 0 변경 확인(296467b..HEAD diff에 core 파일 없음).

## Threat Model Coverage
- T-04-04 (Information Disclosure, DB 자격): mitigate — USER/PASS를 db-cred secretKeyRef로만 주입, 평문 리터럴 부재 grep 게이트 통과(GATE_OK) ✓
- T-04-05 (Spoofing, 대칭키 불일치/dev키): mitigate — api-gateway.yaml과 동일 jwt-secret Secret(name jwt-secret, key JWT_SECRET) 공유 + SPRING_PROFILES_ACTIVE=prod fail-fast ✓
- T-04-06 (Tampering, 미인증 직접 접근): accept — user-service는 공개 경로 서비스, default-deny는 이 phase 범위 밖(Open Question 2) ✓

새 위협 표면 발견 없음(신규 Deployment/Service DB·JWT 경계는 threat_model에 이미 등록됨).

## Next Phase Readiness
- 신규 2서비스(api-gateway 04-01, user-service 04-02) 배포 계약 완성 — DEPLOY-01/03 충족.
- 04-03(NetworkPolicy·payment Ingress 이관)이 딛고 갈 user-service Service(app=user-service:8085)·라벨 확정.
- 배포 시 jwt-secret의 REPLACE_ME_AT_DEPLOY를 gateway·user-service 동일 256-bit HS256 키로 교체 필요(불일치 시 전면 401).

## Self-Check: PASSED
- infra/k8s/apps/user-service.yaml: FOUND
- infra/k8s/apps/config.yaml: FOUND (modified)
- Commit 6af6852: FOUND
- Commit c425699: FOUND
- Java/core changes since 296467b: NONE (게이트 통과)

---
*Phase: 04-deploy-manifests*
*Completed: 2026-07-30*
