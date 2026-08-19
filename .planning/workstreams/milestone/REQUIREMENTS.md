# REQUIREMENTS — Milestone 1 (현재 인프라 검증 & 개선)

이 요구사항은 **이미 구현된 시스템의 검증과 개선**에 한정한다. 신규 서비스/기능은
포함하지 않는다(→ 향후 마일스톤). 근거 출처는 ingest intel(`.planning/intel/`) +
codebase map(`.planning/codebase/`) + CLAUDE.md 불변식.

## VALID — 검증 & 실측 (Validation)

### VALID-01: 재현 가능한 처리량/지연 실측 절차

현재 인프라에서 취소 경로의 처리량 천장(~190 rps knee, ~220 절벽)과 p95/p99 지연을
open-model 도착률 스윕(`constant-arrival-rate`)으로 재현하고 절차를 문서화한다.
멱등 리플레이 함정(기취소 재취소 → risk 미경유로 p95 가짜 하락) 차단 게이트 포함.

- 출처: docs/load-test/capacity-planning.md §1a, aws-run-plan-2026-07.md,
  measurement-journey.md

### VALID-02: USE-method 포화 진단 관측 키트

~185~190 rps 벽의 병목(payment_db)을 USE decision tree 로 실측 리그에서 재확인할 수 있는
관측 키트가 동작한다: Tomcat thread 메트릭, HikariCP pool(Active/Pending/Idle),
payment→risk hop latency(Micrometer http.client.requests), Grafana USE 대시보드.

- 출처: docs/superpowers/specs/2026-07-11-saturation-diagnosis-kit-design.md,
  docs/load-test/saturation-diagnosis.md

## OPS — 운영성 & 온콜 (Operability)

### OPS-01: 3-view 시스템 대시보드 + white-box 관측

Inbound/App/Infra 3행 Grafana 대시보드(system-views.json)와 opt-in white-box 관측
(OTel 트레이스 → Tempo, 요청당 쿼리수)이 런타임 토글(`OTEL_JAVAAGENT`,
`LOADTEST_QUERYCOUNT_ENABLED`)로 동작한다.

- 출처: system-views-dashboard-design.md, loadtest-whitebox-observability-design.md

### OPS-02: oncall 스킬 프로젝트 자립화

oncall-triage/pr/log 스킬의 per-project 하드코딩(petclinic 잔재)을 제거하고,
이 프로젝트의 self-contained `oncall-target.yml` 디스크립터(판별표·인시던트 메모리
스키마)로 알림 진단이 동작한다.

- 출처: docs/superpowers/specs/2026-07-13-oncall-skills-target-abstraction-design.md
- 참고: Grafana→Slack 알림 배선은 이미 완료(commit 2a29590) — 이 요구사항 밖.

## RESIL — 정합성 & 복구 갭 (스케일아웃에서 드러남)

### RESIL-01: ProcessingRecovery 복구 경로 완결

`PgCancelHttpClient.getStatus()` 와 `RiskManagementHttpClient.isCharged()` 의 스텁
(`UnsupportedOperationException`)을 구현해, PROCESSING 5분 초과 건을 PG사 조회 →
TX3 재실행/보상으로 자동 복구한다. 수동 개입 없이 stale PROCESSING 이 수렴.

- 출처: .planning/codebase/CONCERNS.md (Missing ProcessingRecoveryService Impl),
  scheduler-enhancement-design.md

### RESIL-02: 멀티파드 동시 취소 멱등 응답

동일 취소가 다른 payment 파드에 동시 착지할 때, 레이스 패자가 500(INTERNAL_ERROR)
대신 멱등 응답(진행 중/완료 결과, 200)을 반환한다. PENDING INSERT 의 DuplicateKey
위반을 잡아 기존 CancelRequest 상태를 멱등 반환.

- 출처: docs/load-test/k3s-scaleout-results.md 실험 ② (정직한 발견)

### RESIL-03: ProcessingRecovery 동시성 가드

멀티 인스턴스 스케줄러 동시 실행에 안전하도록: (a) pg_retry_count 를 객체 mutation+save
가 아닌 DB 원자 UPDATE 로, (b) cancelRequestId 단위 분산락/멱등 가드로 동일 건 중복
복구를 차단. Testcontainers 동시 실행 테스트 포함.

- 출처: .planning/codebase/CONCERNS.md (Processing Recovery — Incomplete Error Scenarios)

## DEPLOY — 무중단 운영 (Scale-out Operability)

### DEPLOY-01: 무중단 롤링배포 매니페스트 고정

k3s 매니페스트에 preStop drain(`sleep 8`)과 `maxSurge:0/maxUnavailable:1` 을 반영해
롤링배포 5xx=0 을 재현한다. 전용 노드풀 surge 데드락(required anti-affinity 충돌)
회피 포함. 노드 drain HA(fail 0%)도 anti-affinity 로 유지.

- 출처: docs/load-test/k3s-scaleout-results.md 실험 ④⑤, k3s-scaleout-phase-c 런북

## PERF — 용량 개선 (Capacity Improvement)

### PERF-01: payment_db 병목 완화 레버 실측·적용

확정된 병목(payment_db, CPU+iowait+커밋 혼합)에 대해 천장을 실제로 올리는 on-target
레버를 실측으로 검증·적용한다: (a) storage IOPS A/B(gp3 프로비저닝 IOPS↑ / io2),
(b) 취소당 커밋/round-trip 감축(이미 이력 6→4 완료; 추가 statement 축소 여지).
개입 전후 무릎(knee) 이동을 측정해 효과를 인과적으로 확증.

- 출처: capacity-planning.md §6a·§7, k3s-scaleout-results.md ③ (병목 규명)

---

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| VALID-01 | Phase 1 | Pending |
| VALID-02 | Phase 1 | Pending |
| OPS-01 | Phase 1 | Pending |
| RESIL-01 | Phase 2 | Complete |
| RESIL-02 | Phase 2 | Complete |
| RESIL-03 | Phase 2 | Complete |
| OPS-02 | Phase 3 | Pending |
| DEPLOY-01 | Phase 3 | Pending |
| PERF-01 | Phase 4 | Pending |

**Coverage:** 9/9 requirements mapped ✓ (no orphans)

## Deferred (Milestone 1 범위 밖)

| Item | 사유 | 향후 |
|------|------|------|
| REQ-scale-blog-series | 완료 작업의 에디토리얼 산출물, 소프트웨어 변경 아님 | 콘텐츠 트랙 |
| product-service (상품/SKU/재고) | 미구현 신규 모듈 | M2 후보 |
| 카운터 샤딩 / admission control | 핫 가맹점 레버, 목표 DAU 임계(~3천만) 미도달 | 트리거 시 |
| user/product/JWT 확장 | 신규 기능 | 향후 |
