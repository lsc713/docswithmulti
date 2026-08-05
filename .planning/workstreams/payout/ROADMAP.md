---
milestone: v1.0
milestone_name: 정산 지급 실행 payout
workstream: payout
granularity: standard
last_updated: 2026-08-04
---

# Roadmap — v1.0 정산 지급 실행 payout (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-04-settlement-payout-design.md`
요구사항: `.planning/workstreams/payout/REQUIREMENTS.md`

목표: FINALIZED 정산의 net을 가맹점 계좌로 실제 지급. 관리자 승인 → 목 은행 이체 제출 → **웹훅(1차)+폴(backstop)** 확인으로 PAID/FAILED 수렴. payout은 settlement-only 추가 — payment 무접촉(INV-01).

전략: **tracer-first 수직 슬라이스**. Phase 1이 계좌→승인→제출→확인(웹훅·폴)→PAID happy-path를 얇게 관통하며 모듈배선(BankTransferPort 목·웹훅·폴 스케줄러·Redis)과 INV(settlement-only)를 증명한다. Phase 2가 이중지급 방지·실패 재시도·DEAD·서명/멱등 엣지를 하드닝한다.

## Phases

- [x] **Phase 1: 계좌 + 승인·제출 + 확인→PAID (tracer)** - 계좌 설정, FINALIZED 승인→목 이체 제출, 웹훅/폴로 PROCESSING→PAID 종단 관통, settlement-only 증명
- [x] **Phase 2: 이중지급 방지 + 실패 재시도 + 엣지 하드닝** ✅ GOAL ACHIEVED (4/4, settlement-only, 513 tests green) - 중복승인 409·net 가드, FAILED 재시도·DEAD+알림, 웹훅 서명·순서무관 수렴 멱등, INV 게이트

## Phase Details

### Phase 1: 계좌 + 승인·제출 + 확인→PAID (tracer)
**Goal**: 가맹점 지급계좌를 설정하고, 관리자가 FINALIZED 정산을 승인하면 payout이 생성·목 은행 제출되며, 이체 결과를 웹훅(1차) 또는 폴(backstop)로 받아 PROCESSING→PAID로 확정한다 — payment 무접촉.
**Depends on**: Nothing (첫 phase — settlement v1.0 위)
**Requirements**: ACCT-01, ACCT-02, PAY-01, PAY-03, CONFIRM-01, CONFIRM-02, CONFIRM-03, MOCK-01, INV-01
**Success Criteria** (what must be TRUE):
  1. `PUT/GET /v1/settlements/payout-account/{merchantId}`로 계좌를 upsert·조회한다(빈 값 400, 미존재 404). Flyway V2가 `merchant_payout_account`·`payout` 2테이블을 생성한다.
  2. `POST /v1/settlements/{id}/payout`가 FINALIZED·active 계좌·net>0을 확인하고 payout PROCESSING을 생성(amount=net 스냅샷, transfer_ref=id)하며 `BankTransferPort.submit`을 호출한다. `GET .../payout`이 상태를 반환한다.
  3. `POST /v1/payouts/callback`(서명검증)과 Redisson 폴 스케줄러(`getStatus`)가 **같은 공유 `applyResult`**(status-guarded UPDATE `WHERE status='PROCESSING'`)를 타 PROCESSING→PAID(paid_at)로 확정한다. 웹훅·폴 어느 쪽이 먼저 와도 종단은 1회만 반영된다.
  4. `BankTransferPort` + `@Profile("local") MockBankTransferClient`가 이체를 시뮬레이션한다(payment PgCancelPort 패턴). settlement-service에 Redis(Redisson)·HTTP 클라이언트가 배선되고 Testcontainers(MySQL+Redis)로 기동한다.
  5. payment/order/product/merchant diff 0 — payout은 settlement-service 국한, Flyway V2만 추가(V1 무변경), 기존 정산·리컨실 통합테스트 무회귀. merge-base git diff 게이트가 증명한다.
**Plans**: 3 plans (tracer-first)
- [x] 01-01-PLAN.md — E2E 트레이서: 계좌 upsert → FINALIZED 승인·목 제출 → 서명 웹훅/폴 → PROCESSING→PAID (ACCT-01·PAY-01·CONFIRM-01/02/03·MOCK-01)
- [x] 01-02-PLAN.md — 확장: GET 계좌/payout(404)·빈값 400·순서무관 수렴 멱등 (ACCT-02·PAY-03·CONFIRM-03)
- [x] 01-03-PLAN.md — INV-01 게이트(settlement-only)·4모듈 무회귀

### Phase 2: 이중지급 방지 + 실패 재시도 + 엣지 하드닝
**Goal**: 중복 승인·경합에서 이중지급을 막고, 이체 실패를 재시도·종단 처리하며, 웹훅 서명·순서무관 수렴을 멱등하게 보장한다.
**Depends on**: Phase 1
**Requirements**: PAY-02, RETRY-01, INV-01(재검증)
**Success Criteria** (what must be TRUE):
  1. 이미 payout이 있는 정산 재승인 → 409(기존 반환), 미FINALIZED/계좌없음/net≤0 → 400. `payout.settlement_id` UK + 승인 경합에서 payout은 1건만 생성된다(이중지급 없음).
  2. FAILED payout을 폴 스케줄러가 재제출한다(attempt_count<max, transfer_ref 동일 → 목 은행 dedup, PROCESSING 복귀). attempt_count≥max → 종단 FAILED + `OperationAlertPort` 알림 1회(재알림 억제).
  3. 웹훅 서명 불일치 → 401·상태불변. 웹훅과 폴이 같은 payout에 도착 → `applyResult` status-guarded UPDATE로 종단 1회만 반영(둘째 0행 no-op), 중복콜백 멱등.
  4. INV-01 재검증 — settlement-only diff, Flyway V2만, 4모듈 무회귀.
**Plans**: 3 plans (순차 — 같은 worktree gradle 테스트 레이스 회피)
- [ ] 02-01-PLAN.md — PAY-02: 중복/경합 승인 → 409(기존 payout 반환), DIVE→409 race-loser, 3개 400 가드·404 유지
- [ ] 02-02-PLAN.md — RETRY-01: FAILED 재시도(claim+resubmit, transfer_ref 동일)·종단 DEAD·알림 1회, 기존 poll 틱 배선
- [ ] 02-03-PLAN.md — INV-01 재검증 게이트(settlement-only·V2만·V3 없음)·4모듈 무회귀

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 계좌 + 승인·제출 + 확인→PAID | 3/3 | Complete | 2026-08-05 |
| 2. 이중지급 방지 + 실패 재시도 + 엣지 | 0/3 | Planned | - |
