# ROADMAP — 패션 이커머스 결제 취소 시스템

**Milestone 1:** 현재 인프라 검증 & 개선
**Granularity:** standard · **Phase ID:** sequential
**Coverage:** 9/9 v1 requirements mapped ✓

> 핵심 서비스(payment/order/merchant-limit/risk)는 이미 구현·실측 완료. 이 로드맵은
> 신규 기능이 아니라 **현재 인프라 위 검증 + 개선**만 다룬다. 과거 빌드 플랜은 잠긴
> 맥락. 잠긴 결정은 PROJECT.md `<decisions>` 참조(payment.cancelled = TX3 인라인,
> 병목 = payment_db).

## Phases

- [ ] **Phase 1: 실측 재현 & 병목 관측 기반 확립** - 처리량/지연 천장과 payment_db 병목을 재현 가능한 절차·관측으로 확정
- [x] **Phase 2: 정합성 & 복구 갭 마감** - 스케일아웃에서 드러난 복구 스텁·멀티파드 레이스 결함 제거 (completed 2026-07-29)
- [ ] **Phase 3: 무중단 운영 하드닝** - 롤링배포/노드장애 회복을 매니페스트로 고정하고 oncall 자립화
- [ ] **Phase 4: 용량 개선 레버 실측·적용** - payment_db 천장을 실제로 올리는 on-target 레버 검증·적용

## Phase Details

### Phase 1: 실측 재현 & 병목 관측 기반 확립

**Goal**: 현재 인프라에서 취소 경로의 처리량 천장·지연·병목을 누구나 재현할 수 있는
절차와 관측 위에 올린다. 이후 모든 개선(Phase 2~4)의 측정 기준선을 만든다.
**Depends on**: 없음 (첫 페이즈)
**Requirements**: VALID-01, VALID-02, OPS-01
**Success Criteria** (무엇이 참이어야 하는가):

  1. open-model 도착률 스윕을 실행하면 ~190 rps knee 와 210 절벽(p95 붕괴)이
     문서화된 절차대로 재현되고, 멱등 리플레이 함정이 게이트로 차단된다.

  2. USE 대시보드에서 250 rps 부하 시 payment_db 포화(CPU~95%·iowait·커밋 대기)와
     앱 티어 여유가 한 화면에서 구분되어 보인다.

  3. Inbound/App/Infra 3-view Grafana 대시보드가 뜨고, OTel 트레이스·요청당 쿼리수가
     런타임 토글로 켜지고 꺼진다.
**Plans**: TBD

### Phase 2: 정합성 & 복구 갭 마감

**Goal**: 단일 인스턴스에서는 안 드러나고 멀티파드/스케일아웃에서만 드러난 정합성·복구
결함을 제거한다. stale PROCESSING 이 수동 개입 없이 수렴하고, 동시 취소 레이스가
멱등하게 응답한다.
**Depends on**: Phase 1 (복구/레이스 수정의 회귀를 실측 기준선으로 검증)
**Requirements**: RESIL-01, RESIL-02, RESIL-03
**Success Criteria** (무엇이 참이어야 하는가):

  1. PROCESSING 5분 초과 건이 PG사 조회 → TX3 재실행/보상으로 자동 복구되고,
     getStatus()/isCharged() 가 더 이상 UnsupportedOperationException 을 던지지 않는다.

  2. 동일 취소를 두 payment 파드에 동시 착지시키면, 패자가 500 이 아니라 멱등 응답
     (진행 중/완료, 200)을 반환하고 DB 는 여전히 1건 COMPLETED·이중취소 0.

  3. 두 스케줄러 인스턴스가 같은 CancelRequest 를 동시에 복구해도 pg_retry_count 유실·
     중복 복구 없이 정확히 한 번 처리된다(Testcontainers 동시 실행 테스트 통과).
**Plans**: 3/3 plans executed
**Wave 1**

- [x] 02-01-PLAN.md — RESIL-01: getStatus/isCharged 스텁 제거 + stale PROCESSING 자동복구 (D-01 human-verify 게이트)
- [x] 02-02-PLAN.md — RESIL-02: 멀티파드 동시 취소 레이스 패자 500→멱등 200 (UK catch)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 02-03-PLAN.md — RESIL-03: pg_retry_count 원자 UPDATE 동시성 가드 + Testcontainers IT

### Phase 3: 무중단 운영 하드닝

**Goal**: 스케일아웃 검증에서 확인된 무중단 배포·노드장애 회복을 일회성 실험이 아니라
매니페스트에 고정하고, oncall 대응을 이 프로젝트에 자립화한다.
**Depends on**: Phase 1 (배포/장애 중 fail%·p95 블립을 대시보드로 관측)
**Requirements**: DEPLOY-01, OPS-02
**Success Criteria** (무엇이 참이어야 하는가):

  1. `rollout restart` 시 매니페스트의 preStop drain + maxSurge:0/maxUnavailable:1
     로 5xx=0·max latency 저블립이 재현되고, 전용 노드풀 surge 데드락이 없다.

  2. payment 파드가 얹힌 노드를 drain 해도 fail 0%·자동 self-heal(3/3 복귀)이
     anti-affinity 로 유지된다.

  3. oncall-triage/pr/log 가 프로젝트의 oncall-target.yml 만으로 하드코딩 없이
     이 시스템의 알림을 진단한다.
**Plans**: TBD

### Phase 4: 용량 개선 레버 실측·적용

**Goal**: 확정된 병목(payment_db)에 대해 천장을 실제로 올리는 on-target 레버를
실측으로 검증하고 적용한다. 마일스톤의 헤드라인 개선 — "얼마나 잘 도는지 검증한 뒤
구체적으로 개선한다"의 개선 절반.
**Depends on**: Phase 1 (knee 이동을 동일 실측 절차로 인과 확증), Phase 2 (정합성
결함이 없는 상태에서 성능 개입)
**Requirements**: PERF-01
**Success Criteria** (무엇이 참이어야 하는가):

  1. storage IOPS A/B(gp3 프로비저닝 IOPS↑ 또는 io2)를 실측해 iowait 변화와 무릎
     이동 여부가 숫자로 판정된다(안 오르면 순수 fsync 지연 바운드로 결론).

  2. 취소당 커밋/round-trip 감축 개입 전후로 payment_db statement 수·softirq·knee 가
     측정되고, 효과가 리그 내부 상대차로 확증된다.

  3. 적용된 레버의 무릎 이동이 정직 정정 원칙(숨긴 것까지 기록·크로스리그 절대비교
     금지)에 따라 capacity-planning.md 에 반영된다.
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 실측 재현 & 병목 관측 기반 확립 | 0/? | Not started | - |
| 2. 정합성 & 복구 갭 마감 | 4/3 | Complete    | 2026-07-29 |
| 3. 무중단 운영 하드닝 | 0/? | Not started | - |
| 4. 용량 개선 레버 실측·적용 | 0/? | Not started | - |

## 향후 마일스톤 (high-level sketch only)

- **M2 (후보): product-service + 재고 연동** — 상품/SKU/재고 모듈 구현,
  payment.cancelled 컨슈머로 restock. 신규 기능.

- **M-content: Scale 블로그 시리즈** — 완료된 스케일아웃 서사의 9부 에디토리얼(별 트랙).
- **트리거성: 핫 가맹점 레버** — 카운터 샤딩/admission control, 목표 DAU ~3천만 도달 시.
