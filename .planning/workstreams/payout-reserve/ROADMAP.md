---
milestone: v1.0
milestone_name: 정산 유보금 payout reserve
workstream: payout-reserve
granularity: standard
last_updated: 2026-08-05
---

# Roadmap — v1.0 정산 유보금 payout reserve (settlement-service)

권위 설계: `docs/superpowers/specs/2026-08-05-settlement-payout-reserve-design.md`
요구사항: `.planning/workstreams/payout-reserve/REQUIREMENTS.md`

목표: payout 승인 시 net의 일부를 유보(요율+누적 상한)해 `payout=net−reserve`로 줄이고, hold 만기 후 자체 은행이체로 릴리스(웹훅+폴 확인). 시간기반 롤링 홀드백. reserve는 payout의 평행 미니 상태머신 — settlement-only, payout 코어 무변경.

전략: **hold → release 2 phase**. Phase 1이 유보 정책 + 승인 홀드(net−reserve·HELD 행·조회)를 얹되 하위호환(유보 미설정 → net)을 지키고 payout 409/retry 무회귀를 증명한다. Phase 2가 릴리스 수명주기(만기→자체이체→웹훅/폴 확인→RELEASED, 재시도/DEAD)를 payout 기계 복제로 얹는다.

## Phases

- [ ] **Phase 1: 유보 정책 + 승인 홀드** - reserve config(rate/cap/hold_days) + payout 승인 유보 차감(net−reserve, cap)·HELD 행·조회, 하위호환·payout 코어 무회귀
- [ ] **Phase 2: 유보 릴리스 수명주기** - 만기 릴리스 스케줄러 → 자체이체 → 웹훅/폴 확인 → RELEASED, 재시도·RELEASE_DEAD, INV 게이트

## Phase Details

### Phase 1: 유보 정책 + 승인 홀드
**Goal**: 가맹점 유보 정책을 설정하고, payout 승인 시 net에서 유보(cap 반영)를 차감해 payout=net−reserve로 지급하며 reserve HELD 행을 남긴다 — 유보 미설정 가맹점은 net 그대로(하위호환), payout 코어(409 race·retry) 무변경.
**Depends on**: Nothing (payout v1.0 위)
**Requirements**: RCFG-01, RCFG-02, HOLD-01, HOLD-02, HOLD-03, HOLD-04, MOCK-01, INV-01
**Success Criteria** (what must be TRUE):
  1. `PUT/GET /v1/settlements/reserve-config/{merchantId}`로 정책(rate/cap/holdDays)을 upsert·조회한다(유효성 400, 미존재 404). Flyway V3가 `merchant_reserve_config`·`reserve` 2테이블을 생성한다(V1/V2 무변경).
  2. payout 승인 시 `reserve = min(round(net×rate,2,HALF_UP), max(0, cap−current_held))`를 산정하고 `payout.amount = net − reserve`로 지급하며, reserve>0이면 HELD 행(settlement_id UK, hold_until=today(KST)+holdDays, transfer_ref='RSV-'+settlementId)을 생성한다.
  3. 유보 미설정/비활성/rate 0/cap 소진 → reserve=0 → payout=net, reserve 행 미생성(하위호환). `GET /v1/settlements/{id}/reserve`가 상태를 반환(없으면 404).
  4. 승인 순서가 payout INSERT → reserve INSERT → submit이고 approve()는 non-@Transactional을 유지한다 — 기존 payout 409 race·retry/DEAD 통합테스트가 무회귀로 통과한다.
  5. payment/order/product/merchant diff 0, Flyway V3만 추가. merge-base git diff 게이트가 settlement-only를 증명한다.
**Plans**: 3 plans
- [ ] 01-01-PLAN.md — 유보 데이터 모델(V3) + config/reserve 영속 + ReserveCalculator + approve 유보 배선(net−reserve·HELD 행·하위호환) [HOLD-01/02/03, MOCK-01]
- [ ] 01-02-PLAN.md — 유보 정책 PUT/GET + 유보 상태 GET + 예외/error-catalog [RCFG-01/02, HOLD-04]
- [ ] 01-03-PLAN.md — INV-01 게이트(settlement-only·V3-only·payment 마이그레이션 0) + 4모듈 무회귀 [INV-01]

### Phase 2: 유보 릴리스 수명주기
**Goal**: hold 만기된 유보금을 자체 은행이체로 릴리스하고, 결과를 웹훅(1차)+폴(backstop)로 확인해 RELEASED로 수렴하며, 실패를 재시도·RELEASE_DEAD 처리한다.
**Depends on**: Phase 1
**Requirements**: REL-01, REL-02, REL-03, INV-01(재검증)
**Success Criteria** (what must be TRUE):
  1. Redisson 릴리스 스케줄러가 `status='HELD' AND hold_until < today(KST)`를 선택해 claim(HELD→RELEASING guarded UPDATE rowcount==1)하고 `BankTransferPort.submit('RSV-'+settlementId, account, amount)`를 호출한다(active 계좌 없으면 skip+log·HELD 유지).
  2. 웹훅 `POST /v1/reserves/callback`(서명검증 불일치 401)과 폴 backstop이 공유 `applyReserveResult`(status-guarded UPDATE `WHERE transfer_ref=? AND status='RELEASING'`)를 타 RELEASING→RELEASED(released_at)/RELEASE_FAILED로 확정한다 — 웹훅·폴 어느 쪽이 먼저 와도 종단 1회(둘째 0행 no-op).
  3. RELEASE_FAILED → 스케줄러 재제출(attempt<max, 동일 transfer_ref)→RELEASING 복귀, max 초과 → RELEASE_DEAD + `OperationAlertPort` 알림 1회(재알림 없음). settlement_id UK + transfer_ref + guarded UPDATE로 이중 릴리스 없음.
  4. INV-01 재검증 — settlement-only diff, Flyway V3만, payout 코어 무변경, 4모듈 무회귀.
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. 유보 정책 + 승인 홀드 | 0/3 | Not started | - |
| 2. 유보 릴리스 수명주기 | 0/? | Not started | - |
