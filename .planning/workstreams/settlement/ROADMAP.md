---
milestone: v1.0
milestone_name: 정산 집계 코어
workstream: settlement
granularity: standard
last_updated: 2026-08-03
---

# Roadmap — v1.0 정산 집계 코어 (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-03-settlement-aggregation-design.md`
요구사항: `.planning/workstreams/settlement/REQUIREMENTS.md`

목표: 신규 `settlement-service`(8086)가 완료 결제·취소를 **가맹점×정산주(KST 월~일)** 로 집계해 **수수료+VAT를 뗀 net 지급액을 원장에 확정**한다. 매출/취소는 이벤트 실시간 적재 + 주 마감 배치 리컨실로 누락 보정. payment 취소 코어 diff 0 (CANCEL-01/INV-01).

전략: **tracer-first 수직 슬라이스**. Phase 1이 이미 존재하는 `payment.cancelled` 이벤트만으로 서비스 골격→원장 적재→집계→조회를 얇게 관통하고 INV-01 불변식을 증명한다(신규 payment 코드 0). Phase 2가 매출 이벤트(`payment.completed` 신설)와 수수료·net 산출을 얹는다. Phase 3이 배치 리컨실로 이벤트 누락을 보정하고 FINALIZE로 확정한다.

## Phases

- [x] **Phase 1: 서비스 골격 + 취소 적재 CORE (tracer)** - settlement-service 세우고 기존 payment.cancelled로 취소를 원장에 멱등 적재·주별 집계·조회, payment 코드 변경 0 ✅ GOAL ACHIEVED (5/5, 9 tests, payment diff 0)
- [x] **Phase 2: 매출 이벤트 + 수수료·net 산출** - payment.completed 아웃박스 신설 + settlement 매출 적재, 요율 기반 fee+VAT+net 산출 ✅ GOAL ACHIEVED (5/5, cancel core diff 0, 447 tests green)
- [ ] **Phase 3: 배치 리컨실 + 확정** - 주 마감 리컨실러가 payment DB 대조로 누락 보정 후 OPEN→FINALIZED 확정

## Phase Details

### Phase 1: 서비스 골격 + 취소 적재 CORE (tracer)
**Goal**: 신규 settlement-service가 기존 `payment.cancelled` 이벤트를 구독해 취소를 가맹점×정산주 원장에 멱등 적재하고, 주별 집계 헤더를 갱신하며, 정산 내역을 조회할 수 있다 — payment 모듈 코드 변경 0.
**Depends on**: Nothing (first phase)
**Requirements**: SETUP-01, CANCEL-01, CANCEL-02, QUERY-01, INV-01
**Success Criteria** (what must be TRUE):
  1. `settlement-service`(8086)가 독립 MySQL·헥사고날로 기동하고 Flyway V1이 4테이블(merchant_settlement_config·settlement·settlement_line·processed_settlement_event)을 생성한다.
  2. `payment.cancelled` 수신 시 CANCEL 라인이 `event_id=cancel:{cancelRequestId}` UK로 적재되고, 같은 이벤트 2회 수신 시 라인은 1개다(멱등). 소속 정산주 헤더(merchant×period_start, KST 월요일)가 upsert되고 `cancel_amount`가 원자 증분한다.
  3. `GET /v1/settlements?merchantId&status`가 원장 헤더 목록을, `GET /v1/settlements/{id}`가 헤더+라인 명세를 반환한다.
  4. payment 모듈 diff 0 — settlement는 payment.cancelled를 소비만 하고 payment 코드/스키마를 전혀 건드리지 않는다. 기존 취소·재고 통합테스트 무회귀. git diff(merge-base) 게이트가 이를 증명한다.
**Plans**: 2 plans
- [x] 01-01-PLAN.md — tracer + 원장 코어: settlement-service 골격+Flyway V1+payment.cancelled 구독→CANCEL 라인 멱등 적재+주별 헤더 upsert/증분+조회 API (SETUP-01/CANCEL-01/CANCEL-02/QUERY-01) ✅ 9 tests green
- [x] 01-02-PLAN.md — INV-01 게이트+무회귀: merge-base git diff로 payment diff 0 증명 + 전체 스위트 그린 (INV-01) ✅ 431 tests green

### Phase 2: 매출 이벤트 + 수수료·net 산출
**Goal**: payment-service가 결제 생성 시 `payment.completed`를 아웃박스로 발행하고, settlement가 이를 구독해 매출을 원장에 적재하며, 요율 기반으로 수수료+VAT+net을 산출한다.
**Depends on**: Phase 1
**Requirements**: SALE-01, SALE-02, FEE-01, FEE-02
**Success Criteria** (what must be TRUE):
  1. payment-service가 결제 생성 TX에서 `payment_event_outbox`에 `payment.completed`를 원자 INSERT하고 발행한다(cancel outbox 패턴 복제). 취소 TX1/2/3·cancel outbox·재고 경로는 diff 0을 유지한다(INV-01 재검증).
  2. settlement가 `payment.completed`를 구독해 `event_id=sale:{paymentKey}` UK로 SALE 라인을 적재하고(멱등), 정산주 헤더 `gross_amount`가 원자 증분한다.
  3. `merchant_settlement_config`(요율)로 `fee=round(gross×rate,2,HALF_UP)`, `vat=round(fee×0.1,2,HALF_UP)`, `net=gross−cancel−fee−vat`가 정확히 산출된다(BigDecimal scale 2 HALF_UP, 경계값 포함). 요율 미설정 가맹점은 net 확정을 보류한다.
**Plans**: 3 plans
- [x] 02-01-PLAN.md — SALE 수직 슬라이스(tracer): payment.completed 아웃박스(V19, 생성 TX INSERT + 폴 발행, completedAt Z형) + settlement SALE 적재/gross 원자증분/멱등/KST주경계 (SALE-01/SALE-02) [wave 1] ✅
- [x] 02-02-PLAN.md — fee/VAT/net 순수 계산기 + merchant_settlement_config 엔티티/조회·upsert·PUT 경로(요율 검증) (FEE-01/FEE-02) [wave 1] ✅
- [x] 02-03-PLAN.md — INV-01 재검증 게이트(취소 CORE diff 0 denylist + 생성경로 allowlist) + 4모듈 무회귀 (INV-01) [wave 2] ✅ INV01_PASS · 447 tests green

### Phase 3: 배치 리컨실 + 확정
**Goal**: 주 마감 배치 리컨실러가 payment DB를 대조해 이벤트 누락분을 보정 적재하고, gross/cancel을 재검증한 뒤 fee/vat/net을 확정하며 OPEN→FINALIZED로 전이한다.
**Depends on**: Phase 2
**Requirements**: RECON-01, RECON-02, RECON-03
**Success Criteria** (what must be TRUE):
  1. payment-service에 리컨실 전용 조회 API `GET /v1/payments/settlement?merchantId&from&to`(읽기 전용)가 신설되어 기간 내 완료 결제 + 취소를 반환한다(모듈간 DB 직접접근 없음).
  2. Redisson 분산락 스케줄러가 주 마감된 OPEN 원장을 대상으로 payment 조회 결과와 대조해 이벤트 누락 SALE/CANCEL 라인을 보정 적재한다 — 이미 적재된 event_id는 UK로 중복 무시된다(이벤트 유실 시나리오에서 최종 정합 복구).
  3. 리컨실 후 gross/cancel 재검증 → fee/vat/net 확정 → OPEN→FINALIZED 전이(finalized_at). FINALIZED 헤더는 금액·라인이 불변이고, 마감 후 도착 이벤트/불일치는 `OperationAlertPort`로 알림된다.
**Plans**: 3 plans
- [ ] 03-01-PLAN.md — RECON-03 payment 읽기전용 조회 API `GET /v1/payments/settlement`(신규 PaymentSettlement* 파일, SALE=created_at·CANCEL=completed_at 독립 윈도우, 마이그레이션 0) (RECON-03) [wave 1]
- [ ] 03-02-PLAN.md — settlement 모듈 배선(Redisson·HTTP·alert·@EnableScheduling·TestRedissonConfig) + 리컨실→확정 tracer(주마감 OPEN 대조·record() 보정적재·Σlines 재검증·status-guarded OPEN→FINALIZED) + FINALIZED 불변가드·요율미설정 유예·drift 알림 (RECON-01/02) [wave 1]
- [ ] 03-03-PLAN.md — INV-01 Phase 3 게이트(취소 CORE diff 0 denylist + PaymentSettlement allowlist 확장 + payment 마이그레이션 0) + 4모듈 무회귀 (INV-01) [wave 2]

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 서비스 골격 + 취소 적재 CORE | 2/2 | Complete | 2026-08-04 |
| 2. 매출 이벤트 + 수수료·net 산출 | 3/3 | Complete | 2026-08-04 |
| 3. 배치 리컨실 + 확정 | 0/3 | Planned | - |
